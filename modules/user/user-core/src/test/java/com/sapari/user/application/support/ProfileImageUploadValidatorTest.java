package com.sapari.user.application.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.user.application.dto.ProfileImageStoreCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;

@DisplayName("프로필 이미지 검증/재인코딩 서비스 테스트")
class ProfileImageUploadValidatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ProfileImageUploadValidator validator = new ProfileImageUploadValidator();

    @Test
    @DisplayName("JPEG 이미지를 검증하고 저장용 JPEG 바이트로 재인코딩한다")
    void validateReencodesJpegForStorage() throws IOException {
        byte[] original = imageBytesWithTrailingJunk("jpg", 20, 20);

        ProfileImageStoreCommand command = validator.validate(USER_ID, "profile.JPG", "image/jpeg", original);

        assertThat(command.userId()).isEqualTo(USER_ID);
        assertThat(command.normalizedExtension()).isEqualTo("jpg");
        assertThat(command.contentType()).isEqualTo("image/jpeg");
        assertThat(command.content()).isNotEmpty();
        assertThat(command.content()).isNotEqualTo(original);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(command.content()))).isNotNull();
    }

    @Test
    @DisplayName("PNG 이미지를 검증하고 저장용 PNG 바이트로 재인코딩한다")
    void validateReencodesPngForStorage() throws IOException {
        byte[] original = imageBytesWithTrailingJunk("png", 20, 20);

        ProfileImageStoreCommand command = validator.validate(USER_ID, "profile.png", "image/png", original);

        assertThat(command.normalizedExtension()).isEqualTo("png");
        assertThat(command.contentType()).isEqualTo("image/png");
        assertThat(command.content()).isNotEqualTo(original);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(command.content()))).isNotNull();
    }

    @Test
    @DisplayName("파일이 없거나 비어 있으면 거부한다")
    void validateRejectsMissingFile() {
        assertUserError(() -> validator.validate(USER_ID, "profile.jpg", "image/jpeg", null),
                UserErrorCode.PROFILE_IMAGE_REQUIRED);
        assertUserError(() -> validator.validate(USER_ID, "profile.jpg", "image/jpeg", new byte[0]),
                UserErrorCode.PROFILE_IMAGE_REQUIRED);
    }

    @Test
    @DisplayName("프로필 이미지 크기가 5MiB를 넘으면 거부한다")
    void validateRejectsTooLargeFile() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        tooLarge[0] = (byte) 0xFF;
        tooLarge[1] = (byte) 0xD8;
        tooLarge[2] = (byte) 0xFF;

        assertUserError(() -> validator.validate(USER_ID, "profile.jpg", "image/jpeg", tooLarge),
                UserErrorCode.PROFILE_IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("허용하지 않는 확장자는 magic bytes를 보기 전 거부한다")
    void validateRejectsUnsupportedExtension() throws IOException {
        byte[] png = imageBytes("png", 20, 20);

        assertUserError(() -> validator.validate(USER_ID, "profile.gif", "image/gif", png),
                UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
        assertUserError(() -> validator.validate(USER_ID, "profile.svg", "image/svg+xml", png),
                UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
        assertUserError(() -> validator.validate(USER_ID, "profile.bmp", "image/bmp", png),
                UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
        assertUserError(() -> validator.validate(USER_ID, "profile.tiff", "image/tiff", png),
                UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
        assertUserError(() -> validator.validate(USER_ID, "profile.heic", "image/heic", png),
                UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("Content-Type은 최종 신뢰하지 않지만 허용 목록 1차 검사는 통과해야 한다")
    void validateRejectsUnsupportedContentType() throws IOException {
        byte[] jpg = imageBytes("jpg", 20, 20);

        assertUserError(() -> validator.validate(USER_ID, "profile.jpg", "image/svg+xml", jpg),
                UserErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("확장자와 Content-Type이 JPEG여도 magic bytes가 다르면 거부한다")
    void validateRejectsMagicBytesMismatch() throws IOException {
        byte[] png = imageBytes("png", 20, 20);

        assertUserError(() -> validator.validate(USER_ID, "profile.jpg", "image/jpeg", png),
                UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT);
    }

    @Test
    @DisplayName("magic bytes가 맞아도 이미지로 decode되지 않으면 거부한다")
    void validateRejectsUndecodableImage() {
        byte[] fakeJpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02};

        assertUserError(() -> validator.validate(USER_ID, "profile.jpg", "image/jpeg", fakeJpeg),
                UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT);
    }

    @Test
    @DisplayName("WebP 이미지는 magic bytes와 decode를 통과하면 PNG로 재인코딩한다")
    void validateReencodesWebpToPngForStorage() {
        byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

        ProfileImageStoreCommand command = validator.validate(USER_ID, "profile.webp", "image/webp", webp);

        assertThat(command.normalizedExtension()).isEqualTo("png");
        assertThat(command.contentType()).isEqualTo("image/png");
        assertThat(command.content()).isNotEmpty();
    }

    @Test
    @DisplayName("WebP 시그니처는 허용 형식으로 보되 decode되지 않으면 내용 오류로 거부한다")
    void validateTreatsWebpAsAllowedTypeBeforeDecode() {
        byte[] fakeWebp = new byte[] {
                0x52, 0x49, 0x46, 0x46,
                0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50,
                0x01, 0x02, 0x03, 0x04
        };

        assertUserError(() -> validator.validate(USER_ID, "profile.webp", "image/webp", fakeWebp),
                UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT);
    }

    @Test
    @DisplayName("해상도가 2048x2048을 넘으면 거부한다")
    void validateRejectsTooLargeResolution() throws IOException {
        byte[] wide = imageBytes("png", 2049, 10);
        byte[] tall = imageBytes("png", 10, 2049);

        assertUserError(() -> validator.validate(USER_ID, "profile.png", "image/png", wide),
                UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT);
        assertUserError(() -> validator.validate(USER_ID, "profile.png", "image/png", tall),
                UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT);
    }

    private void assertUserError(Runnable action, UserErrorCode expectedErrorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(UserException.class)
                .extracting("errorCode")
                .isEqualTo(expectedErrorCode);
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
}
