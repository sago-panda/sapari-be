package com.sapari.global.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("이미지 파일 검증/재인코딩 공통 validator 테스트")
class ImageFileValidatorTest {

    @Test
    @DisplayName("JPEG 이미지를 검증하고 저장용 JPEG 바이트로 재인코딩한다")
    void validateReencodesJpegForStorage() throws IOException {
        byte[] original = imageBytesWithTrailingJunk("jpg", 20, 20);

        ImageFileValidator.ValidatedImageFile image =
                ImageFileValidator.validate("profile.JPG", "image/jpeg", original);

        assertThat(image.normalizedExtension()).isEqualTo("jpg");
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.content()).isNotEmpty();
        assertThat(image.content()).isNotEqualTo(original);
        assertThat(ImageIO.read(new ByteArrayInputStream(image.content()))).isNotNull();
    }

    @Test
    @DisplayName("PNG 이미지를 검증하고 저장용 PNG 바이트로 재인코딩한다")
    void validateReencodesPngForStorage() throws IOException {
        byte[] original = imageBytesWithTrailingJunk("png", 20, 20);

        ImageFileValidator.ValidatedImageFile image =
                ImageFileValidator.validate("profile.png", "image/png", original);

        assertThat(image.normalizedExtension()).isEqualTo("png");
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.content()).isNotEqualTo(original);
        assertThat(ImageIO.read(new ByteArrayInputStream(image.content()))).isNotNull();
    }

    @Test
    @DisplayName("파일이 없거나 비어 있으면 REQUIRED로 거부한다")
    void validateRejectsMissingFile() {
        assertValidationError(() -> ImageFileValidator.validate("profile.jpg", "image/jpeg", null),
                ImageFileValidator.FailureReason.REQUIRED);
        assertValidationError(() -> ImageFileValidator.validate("profile.jpg", "image/jpeg", new byte[0]),
                ImageFileValidator.FailureReason.REQUIRED);
    }

    @Test
    @DisplayName("파일 크기가 5MiB를 넘으면 TOO_LARGE로 거부한다")
    void validateRejectsTooLargeFile() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        tooLarge[0] = (byte) 0xFF;
        tooLarge[1] = (byte) 0xD8;
        tooLarge[2] = (byte) 0xFF;

        assertValidationError(() -> ImageFileValidator.validate("profile.jpg", "image/jpeg", tooLarge),
                ImageFileValidator.FailureReason.TOO_LARGE);
    }

    @Test
    @DisplayName("재인코딩 후 저장 바이트가 5MiB를 넘으면 TOO_LARGE로 거부한다")
    void validateRejectsWhenReencodedContentExceedsMaxSize() throws IOException {
        byte[] compressedPng = compressedRandomPngBytes(1850, 1850);

        assertThat(compressedPng).hasSizeLessThanOrEqualTo(5 * 1024 * 1024);
        assertValidationError(() -> ImageFileValidator.validate("profile.png", "image/png", compressedPng),
                ImageFileValidator.FailureReason.TOO_LARGE);
    }

    @Test
    @DisplayName("허용하지 않는 확장자는 magic bytes를 보기 전 UNSUPPORTED_TYPE으로 거부한다")
    void validateRejectsUnsupportedExtension() throws IOException {
        byte[] png = imageBytes("png", 20, 20);

        assertValidationError(() -> ImageFileValidator.validate("profile.gif", "image/gif", png),
                ImageFileValidator.FailureReason.UNSUPPORTED_TYPE);
        assertValidationError(() -> ImageFileValidator.validate("profile.svg", "image/svg+xml", png),
                ImageFileValidator.FailureReason.UNSUPPORTED_TYPE);
        assertValidationError(() -> ImageFileValidator.validate("profile.bmp", "image/bmp", png),
                ImageFileValidator.FailureReason.UNSUPPORTED_TYPE);
        assertValidationError(() -> ImageFileValidator.validate("profile.tiff", "image/tiff", png),
                ImageFileValidator.FailureReason.UNSUPPORTED_TYPE);
        assertValidationError(() -> ImageFileValidator.validate("profile.heic", "image/heic", png),
                ImageFileValidator.FailureReason.UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("Content-Type이 허용 목록과 맞지 않으면 UNSUPPORTED_TYPE으로 거부한다")
    void validateRejectsUnsupportedContentType() throws IOException {
        byte[] jpg = imageBytes("jpg", 20, 20);

        assertValidationError(() -> ImageFileValidator.validate("profile.jpg", "image/svg+xml", jpg),
                ImageFileValidator.FailureReason.UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("확장자와 Content-Type이 JPEG여도 magic bytes가 다르면 INVALID_CONTENT로 거부한다")
    void validateRejectsMagicBytesMismatch() throws IOException {
        byte[] png = imageBytes("png", 20, 20);

        assertValidationError(() -> ImageFileValidator.validate("profile.jpg", "image/jpeg", png),
                ImageFileValidator.FailureReason.INVALID_CONTENT);
    }

    @Test
    @DisplayName("magic bytes가 맞아도 이미지로 decode되지 않으면 INVALID_CONTENT로 거부한다")
    void validateRejectsUndecodableImage() {
        byte[] fakeJpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02};

        assertValidationError(() -> ImageFileValidator.validate("profile.jpg", "image/jpeg", fakeJpeg),
                ImageFileValidator.FailureReason.INVALID_CONTENT);
    }

    @Test
    @DisplayName("WebP 이미지는 magic bytes와 decode를 통과하면 PNG로 재인코딩한다")
    void validateReencodesWebpToPngForStorage() {
        byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

        ImageFileValidator.ValidatedImageFile image =
                ImageFileValidator.validate("profile.webp", "image/webp", webp);

        assertThat(image.normalizedExtension()).isEqualTo("png");
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.content()).isNotEmpty();
    }

    @Test
    @DisplayName("WebP 시그니처는 허용 형식으로 보되 decode되지 않으면 INVALID_CONTENT로 거부한다")
    void validateTreatsWebpAsAllowedTypeBeforeDecode() {
        byte[] fakeWebp = new byte[] {
                0x52, 0x49, 0x46, 0x46,
                0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50,
                0x01, 0x02, 0x03, 0x04
        };

        assertValidationError(() -> ImageFileValidator.validate("profile.webp", "image/webp", fakeWebp),
                ImageFileValidator.FailureReason.INVALID_CONTENT);
    }

    @Test
    @DisplayName("해상도가 2048x2048을 넘으면 INVALID_CONTENT로 거부한다")
    void validateRejectsTooLargeResolution() throws IOException {
        byte[] wide = imageBytes("png", 2049, 10);
        byte[] tall = imageBytes("png", 10, 2049);

        assertValidationError(() -> ImageFileValidator.validate("profile.png", "image/png", wide),
                ImageFileValidator.FailureReason.INVALID_CONTENT);
        assertValidationError(() -> ImageFileValidator.validate("profile.png", "image/png", tall),
                ImageFileValidator.FailureReason.INVALID_CONTENT);
    }

    private void assertValidationError(Runnable action, ImageFileValidator.FailureReason expectedReason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ImageFileValidator.ImageFileValidationException.class)
                .extracting("reason")
                .isEqualTo(expectedReason);
    }

    private byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, Color.ORANGE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private byte[] imageBytesWithTrailingJunk(String format, int width, int height) throws IOException {
        byte[] encoded = imageBytes(format, width, height);
        byte[] withJunk = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, withJunk, 0, encoded.length);
        withJunk[encoded.length] = 0x01;
        withJunk[encoded.length + 1] = 0x02;
        withJunk[encoded.length + 2] = 0x03;
        withJunk[encoded.length + 3] = 0x04;
        return withJunk;
    }

    private byte[] compressedRandomPngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = random.nextInt(256);
                image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
            }
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOutputStream);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(0.0f);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
