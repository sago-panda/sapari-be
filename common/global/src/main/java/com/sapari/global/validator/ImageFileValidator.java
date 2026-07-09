package com.sapari.global.validator;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.util.StringUtils;

/**
 * 사용자 업로드 이미지 원본을 저장 가능한 이미지 바이트와 서버 기준 metadata로 정규화하는 공통 validator다.
 *
 * <p>실패 시에는
 * {@link ImageFileValidationException}과 {@link FailureReason}제공하고, 호출 도메인이 해당 실패 이유를
 * 자기 도메인의 ErrorCode로 매핑한다.</p>
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public final class ImageFileValidator {

    private static final int MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 최대 5MiB(5,242,880 bytes)까지만 허용한다.
    private static final int MAX_WIDTH = 2048; // 디코딩 후 가로가 2048px을 넘으면 과도한 리소스 사용으로 거부한다.
    private static final int MAX_HEIGHT = 2048; // 디코딩 후 세로가 2048px을 넘으면 과도한 리소스 사용으로 거부한다.

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );

    /**
     * 이미지 원본을 검증하고 저장용 바이트로 재인코딩한다.
     *
     * <p>파일명과 Content-Type은 클라이언트가 제공한 힌트일 뿐이므로, 최종 허용 여부는 magic bytes,
     * ImageIO decode, 해상도 제한, 재인코딩 성공 여부로 확정한다. 반환된 content는 storage adapter가
     * 그대로 신뢰할 수 있는 서버 정규화 결과여야 한다.</p>
     */
    public static ValidatedImageFile validate(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        validatePresent(content); // 파일 파트가 실제로 전달되었는지(null/0바이트) 확인한다.
        validateSize(content); // 원본 바이트 크기가 5MiB 제한을 넘지 않는지 확인한다.

        String extension = normalizeExtension(originalFilename);
        validateAllowedExtension(extension); // 서버가 지원하는 확장자(jpg/jpeg/png/webp)인지 확인한다.
        validateContentType(extension, contentType); // 요청 Content-Type이 확장자와 일치하는지 1차 힌트로 확인한다.
        validateMagicBytes(extension, content); // 파일 앞부분의 실제 signature가 확장자와 일치하는지 확인한다.

        validateResolutionBeforeDecode(content); // 전체 픽셀 decode 전에 이미지 header 기준 해상도가 정책 제한 이내인지 먼저 확인한다.
        BufferedImage decodedImage = decode(content); // ImageIO로 실제 이미지 픽셀을 읽을 수 있는지 확인한다.
        validateResolution(decodedImage); // 디코딩된 이미지의 가로·세로가 정책 제한 이내인지 확인한다.

        ReencodedImage reencodedImage = reencode(decodedImage, extension); // metadata/trailing bytes 제거를 위해 저장용으로 재인코딩한다.
        validateSize(reencodedImage.content()); // WebP/JPEG 등을 PNG로 재인코딩하면 원본보다 커질 수 있으므로 저장 바이트도 5MiB 제한을 확인한다.
        return new ValidatedImageFile(
                reencodedImage.normalizedExtension(),
                reencodedImage.contentType(),
                reencodedImage.content()
        );
    }

    /**
     * storage adapter로 넘길 서버 정규화 이미지 결과다.
     *
     * <p>normalizedExtension과 contentType은 원본 요청값이 아니라 재인코딩 후 실제 저장 형식 기준이다.
     * 예를 들어 jpeg 입력은 jpg로 통일하고, WebP 입력은 PNG로 저장한다.</p>
     */
    public record ValidatedImageFile(
            String normalizedExtension,
            String contentType,
            byte[] content
    ) {
    }

    /**
     * 공통 validator가 판단한 실패 이유다.
     *
     * <p>도메인 서비스는 이 값을 각 도메인의 ErrorCode로 변환한다. 이 enum을 클라이언트 응답 코드로
     * 직접 노출하지 않는다.</p>
     */
    public enum FailureReason {
        REQUIRED,
        TOO_LARGE,
        UNSUPPORTED_TYPE,
        INVALID_CONTENT
    }

    /**
     * 이미지 파일 검증 실패를 도메인 경계까지 전달하기 위한 내부 예외다.
     *
     * <p>common/global은 UserException, ProductException 같은 도메인 예외를 알면 안 되므로 이 예외에는
     * 도메인 ErrorCode 대신 {@link FailureReason}만 담는다.</p>
     */
    public static class ImageFileValidationException extends RuntimeException {

        private final FailureReason reason;

        public ImageFileValidationException(FailureReason reason) {
            this.reason = reason;
        }

        public ImageFileValidationException(FailureReason reason, Throwable cause) {
            super(cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }

    /**
     * Multipart 경계에서 파일 파트가 누락되었거나 0바이트로 전달된 요청을 거부한다.
     */
    private static void validatePresent(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ImageFileValidationException(FailureReason.REQUIRED);
        }
    }

    /**
     * 원본 바이트 기준 최대 5MiB까지만 허용해 메모리 처리와 저장 비용을 제한한다.
     */
    private static void validateSize(byte[] content) {
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new ImageFileValidationException(FailureReason.TOO_LARGE);
        }
    }

    /**
     * 원본 파일명에서 마지막 확장자를 추출해 소문자로 정규화한다.
     * 파일명이 없거나 확장자가 없으면 이미지 형식을 판단할 수 없으므로 지원하지 않는 타입으로 처리한다.
     */
    private static String normalizeExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new ImageFileValidationException(FailureReason.UNSUPPORTED_TYPE);
        }
        String filename = originalFilename.trim();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new ImageFileValidationException(FailureReason.UNSUPPORTED_TYPE);
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 서버가 허용한 이미지 확장자인 jpg/jpeg/png/webp만 통과시킨다.
     * gif/svg/bmp/tiff/heic 등은 decoder·보안 정책이 확정되지 않았으므로 저장 파이프라인에 진입시키지 않는다.
     */
    private static void validateAllowedExtension(String extension) {
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ImageFileValidationException(FailureReason.UNSUPPORTED_TYPE);
        }
    }

    /**
     * 클라이언트가 보낸 Content-Type이 확장자 정책과 일치하는지 1차 확인한다.
     * Content-Type은 위조 가능하므로 여기서 통과해도 최종 신뢰하지 않고 magic bytes와 decode 검증을 이어서 수행한다.
     */
    private static void validateContentType(String extension, String contentType) {
        String expectedContentType = ALLOWED_CONTENT_TYPES.get(extension);
        if (!StringUtils.hasText(contentType)
                || !expectedContentType.equals(contentType.trim().toLowerCase(Locale.ROOT))) {
            // Content-Type은 위조 가능하지만, 명백히 다른 타입은 저장 파이프라인에 진입시키지 않는다.
            throw new ImageFileValidationException(FailureReason.UNSUPPORTED_TYPE);
        }
    }

    /**
     * 파일 확장자와 실제 파일 signature가 일치하는지 확인한다.
     * 확장자와 Content-Type을 이미지처럼 꾸민 실행 파일·문서·다른 이미지 형식을 여기서 차단한다.
     */
    private static void validateMagicBytes(String extension, byte[] content) {
        if (isJpegExtension(extension) && isJpeg(content)) {
            return;
        }
        if ("png".equals(extension) && isPng(content)) {
            return;
        }
        if ("webp".equals(extension) && isWebp(content)) {
            return;
        }
        throw new ImageFileValidationException(FailureReason.INVALID_CONTENT);
    }

    /**
     * 전체 픽셀 디코딩 전에 이미지 reader가 제공하는 width/height만 읽어 초대형 이미지를 먼저 거부한다.
     * 파일 크기는 작지만 디코딩 시 큰 메모리를 요구하는 이미지를 실제 픽셀 로딩 전에 차단하기 위한 1차 해상도 방어다.
     */
    private static void validateResolutionBeforeDecode(byte[] content) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new ImageFileValidationException(FailureReason.INVALID_CONTENT);
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ImageFileValidationException(FailureReason.INVALID_CONTENT);
            }

            reader = readers.next();
            reader.setInput(input, true, true);
            validateResolution(reader.getWidth(0), reader.getHeight(0));
        } catch (IOException e) {
            throw new ImageFileValidationException(FailureReason.INVALID_CONTENT, e);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /**
     * ImageIO로 실제 이미지 픽셀을 읽어 깨진 파일이나 signature만 흉내 낸 파일을 거부한다.
     */
    private static BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new ImageFileValidationException(FailureReason.INVALID_CONTENT);
            }
            return image;
        } catch (IOException e) {
            throw new ImageFileValidationException(FailureReason.INVALID_CONTENT, e);
        }
    }

    /**
     * 이미지로 표시·처리할 수 있는 최대 해상도인 2048x2048 이하만 허용한다.
     */
    private static void validateResolution(BufferedImage image) {
        validateResolution(image.getWidth(), image.getHeight());
    }

    private static void validateResolution(int width, int height) {
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new ImageFileValidationException(FailureReason.INVALID_CONTENT);
        }
    }

    /**
     * 검증된 이미지를 저장용 바이트로 다시 인코딩한다.
     * 원본 metadata와 trailing bytes를 제거하고, 저장소에 넘길 확장자와 Content-Type을 서버 정책으로 확정한다.
     */
    private static ReencodedImage reencode(BufferedImage image, String extension) {
        String outputExtension = normalizeOutputExtension(extension);
        String outputContentType = ALLOWED_CONTENT_TYPES.get(outputExtension);
        BufferedImage outputImage = imageForOutputFormat(image, outputExtension);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean written = ImageIO.write(outputImage, outputExtension, out);
            if (!written || out.size() == 0) {
                throw new ImageFileValidationException(FailureReason.INVALID_CONTENT);
            }
            return new ReencodedImage(outputExtension, outputContentType, out.toByteArray());
        } catch (IOException e) {
            throw new ImageFileValidationException(FailureReason.INVALID_CONTENT, e);
        }
    }

    /**
     * 저장소에 남길 확장자를 정규화한다.
     * jpeg는 jpg로 통일하고, Java 기본 ImageIO writer가 보장되지 않는 WebP는 PNG로 저장한다.
     */
    private static String normalizeOutputExtension(String extension) {
        if ("jpeg".equals(extension)) {
            return "jpg";
        }
        // Java 기본 ImageIO는 WebP writer를 보장하지 않으므로, WebP 입력은 decode 후 PNG로 재인코딩해 저장한다.
        if ("webp".equals(extension)) {
            return "png";
        }
        return extension;
    }

    /**
     * JPEG 저장 시 alpha 채널이 있는 이미지를 RGB 이미지로 변환한다.
     * PNG 저장은 투명도를 유지할 수 있으므로 원본 decoded image를 그대로 사용한다.
     */
    private static BufferedImage imageForOutputFormat(BufferedImage source, String outputExtension) {
        if (!"jpg".equals(outputExtension) && !"jpeg".equals(outputExtension)) {
            return source;
        }
        BufferedImage rgbImage = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgbImage.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgbImage;
    }

    /**
     * jpg와 jpeg 확장자를 같은 JPEG 정책으로 처리한다.
     */
    private static boolean isJpegExtension(String extension) {
        return "jpg".equals(extension) || "jpeg".equals(extension);
    }

    /**
     * JPEG SOI marker 계열 signature(FF D8 FF)를 확인한다.
     */
    private static boolean isJpeg(byte[] content) {
        return content.length >= 3
                && unsigned(content[0]) == 0xFF // 255: JPEG SOI 첫 바이트, signed byte 보정을 위해 unsigned로 비교한다.
                && unsigned(content[1]) == 0xD8 // 216: JPEG SOI 두 번째 바이트, signed byte 보정을 위해 unsigned로 비교한다.
                && unsigned(content[2]) == 0xFF; // 255: JPEG marker prefix, signed byte 보정을 위해 unsigned로 비교한다.
    }

    /**
     * PNG 고정 signature(89 50 4E 47 0D 0A 1A 0A)를 확인한다.
     */
    private static boolean isPng(byte[] content) {
        return content.length >= 8
                && unsigned(content[0]) == 0x89 // 137: PNG signature 첫 바이트, signed byte 보정을 위해 unsigned로 비교한다.
                && content[1] == 0x50 // 80: ASCII 'P'.
                && content[2] == 0x4E // 78: ASCII 'N'.
                && content[3] == 0x47 // 71: ASCII 'G'.
                && content[4] == 0x0D // 13: CR(carriage return).
                && content[5] == 0x0A // 10: LF(line feed).
                && content[6] == 0x1A // 26: DOS EOF 문자로 텍스트 처리 오인 가능성을 줄이는 signature 값이다.
                && content[7] == 0x0A; // 10: LF(line feed).
    }

    /**
     * WebP 컨테이너 signature인 RIFF....WEBP를 확인한다.
     */
    private static boolean isWebp(byte[] content) {
        return content.length >= 12
                && content[0] == 0x52 // 82: ASCII 'R'.
                && content[1] == 0x49 // 73: ASCII 'I'.
                && content[2] == 0x46 // 70: ASCII 'F'.
                && content[3] == 0x46 // 70: ASCII 'F'.
                // 4~7번째 바이트는 RIFF chunk size라 이미지마다 달라 검사하지 않는다.
                && content[8] == 0x57 // 87: ASCII 'W'.
                && content[9] == 0x45 // 69: ASCII 'E'.
                && content[10] == 0x42 // 66: ASCII 'B'.
                && content[11] == 0x50; // 80: ASCII 'P'.
    }

    /**
     * Java byte의 signed 표현을 magic bytes 비교용 unsigned 값으로 변환한다.
     * 예를 들어 파일 바이트 0xFF는 Java byte로 읽으면 -1이지만, value & 0xFF를 적용하면 10진수 255가 된다.
     */
    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private record ReencodedImage(String normalizedExtension, String contentType, byte[] content) {
    }
}
