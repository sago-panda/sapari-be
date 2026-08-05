package com.sapari.customer.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sapari.customer.application.dto.SocialProfileImageDownloadResult;
import com.sapari.user.model.ProviderType;

@DisplayName("소셜 프로필 이미지 HTTP downloader 테스트")
class SocialProfileImageHttpDownloaderTest {

    @Test
    @DisplayName("이미지 응답을 제한 크기 안에서 다운로드한다")
    void downloadsProviderImage() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);
        byte[] png = pngBytes();
        server.expect(once(), requestTo("https://k.kakaocdn.net/profile.png"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                "https://k.kakaocdn.net/profile.png"
        );

        assertThat(result).isPresent();
        assertThat(result.get().normalizedExtension()).isEqualTo("png");
        assertThat(result.get().contentType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.get().content()))).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("확장자 없는 Kakao 썸네일 URL은 응답 Content-Type으로 이미지 형식을 판별한다")
    void downloadsKakaoThumbnailWithoutFileExtension() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);
        byte[] jpeg = jpegBytes();
        String kakaoUrl = "https://img1.kakaocdn.net/thumb/R640x640.q70/?fname=default_profile.jpeg";
        server.expect(once(), requestTo(kakaoUrl))
                .andRespond(withSuccess(jpeg, MediaType.IMAGE_JPEG));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                kakaoUrl
        );

        assertThat(result).isPresent();
        assertThat(result.get().normalizedExtension()).isEqualTo("jpg");
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
        server.verify();
    }

    @Test
    @DisplayName("파라미터가 포함된 이미지 Content-Type은 base media type으로 정규화해 검증한다")
    void downloadsImageWhenContentTypeHasParameters() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);
        byte[] jpeg = jpegBytes();
        String kakaoUrl = "https://img1.kakaocdn.net/thumb/R640x640.q70/?fname=default_profile.jpeg";
        server.expect(once(), requestTo(kakaoUrl))
                .andRespond(withSuccess(jpeg, MediaType.parseMediaType("image/jpeg; charset=UTF-8")));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                kakaoUrl
        );

        assertThat(result).isPresent();
        assertThat(result.get().normalizedExtension()).isEqualTo("jpg");
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.get().content()))).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("Content-Type만 이미지인 임의 바이트는 다운로드 결과로 반환하지 않는다")
    void rejectsInvalidImageContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);
        server.expect(once(), requestTo("https://k.kakaocdn.net/profile.png"))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                "https://k.kakaocdn.net/profile.png"
        );

        assertThat(result).isEmpty();
        server.verify();
    }


    @Test
    @DisplayName("maxSizeBytes를 넘는 응답은 비운 결과로 처리한다")
    void rejectsBodyLargerThanLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 2);
        server.expect(once(), requestTo("https://k.kakaocdn.net/profile.png"))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                "https://k.kakaocdn.net/profile.png"
        );

        assertThat(result).isEmpty();
        server.verify();
    }


    private byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private SocialProfileImageHttpDownloader downloader(RestClient restClient, long maxSizeBytes) {
        SocialProfileImageDownloadProperties properties = new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                maxSizeBytes,
                0
        );
        return new SocialProfileImageHttpDownloader(
                restClient,
                properties
        );
    }
}
