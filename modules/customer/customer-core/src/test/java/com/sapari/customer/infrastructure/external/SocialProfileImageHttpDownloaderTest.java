package com.sapari.customer.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sapari.customer.application.dto.SocialProfileImageDownloadResult;
import com.sapari.user.model.ProviderType;

@DisplayName("소셜 프로필 이미지 HTTP downloader 테스트")
class SocialProfileImageHttpDownloaderTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "https://image.example:8080/profile.png",
            "https://user:password@image.example/profile.png",
            "http://127.0.0.1/profile.png"
    })
    @DisplayName("금지된 최초 URI는 HTTP 요청 없이 빈 결과로 처리한다")
    void rejectsUnsafeInitialUriWithoutHttpRequest(String value) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SocialProfileImageHttpDownloader downloader = downloader(builder.build(), 1024);

        assertThat(downloader.download(ProviderType.KAKAO, value)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("안전한 redirect 목적지는 같은 URI 정책을 다시 적용한 뒤 다운로드한다")
    void followsSafeRedirectAfterRevalidation() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SocialProfileImageHttpDownloader downloader = downloader(builder.build(), 1024, 1);
        byte[] png = pngBytes();
        server.expect(once(), requestTo("https://image.example/start"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "https://cdn.example/profile.png"));
        server.expect(once(), requestTo("https://cdn.example/profile.png"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

        assertThat(downloader.download(ProviderType.KAKAO, "https://image.example/start")).isPresent();
        server.verify();
    }

    @Test
    @DisplayName("redirect가 내부 IP literal을 가리키면 두 번째 요청 없이 실패한다")
    void rejectsInternalIpRedirectWithoutSecondRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SocialProfileImageHttpDownloader downloader = downloader(builder.build(), 1024, 1);
        server.expect(once(), requestTo("https://image.example/start"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "http://127.0.0.1/admin"));

        assertThat(downloader.download(ProviderType.KAKAO, "https://image.example/start")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Location 없는 redirect는 빈 결과로 처리한다")
    void rejectsRedirectWithoutLocation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SocialProfileImageHttpDownloader downloader = downloader(builder.build(), 1024, 1);
        server.expect(once(), requestTo("https://image.example/start"))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThat(downloader.download(ProviderType.KAKAO, "https://image.example/start")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("허용 redirect 횟수를 넘으면 다음 목적지를 요청하지 않는다")
    void rejectsRedirectBeyondLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SocialProfileImageHttpDownloader downloader = downloader(builder.build(), 1024, 0);
        server.expect(once(), requestTo("https://image.example/start"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "https://cdn.example/profile.png"));

        assertThat(downloader.download(ProviderType.KAKAO, "https://image.example/start")).isEmpty();
        server.verify();
    }

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
    @DisplayName("URL 확장자와 응답 Content-Type이 다르면 Content-Type을 우선해 이미지 형식을 판별한다")
    void prioritizesResponseContentTypeOverUrlExtension() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);
        byte[] jpeg = jpegBytes();
        server.expect(once(), requestTo("https://k.kakaocdn.net/profile.png"))
                .andRespond(withSuccess(jpeg, MediaType.IMAGE_JPEG));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                "https://k.kakaocdn.net/profile.png"
        );

        assertThat(result).isPresent();
        assertThat(result.get().normalizedExtension()).isEqualTo("jpg");
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.get().content()))).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("응답 Content-Type과 실제 이미지 바이트가 다르면 거부한다")
    void rejectsImageWhenContentTypeDiffersFromActualBytes() throws IOException {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);
        byte[] png = pngBytes();
        server.expect(once(), requestTo("https://k.kakaocdn.net/profile.png"))
                .andRespond(withSuccess(png, MediaType.IMAGE_JPEG));

        Optional<SocialProfileImageDownloadResult> result = downloader.download(
                ProviderType.KAKAO,
                "https://k.kakaocdn.net/profile.png"
        );

        assertThat(result).isEmpty();
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
    @DisplayName("예상하지 못한 프로그래밍 오류는 빈 결과로 숨기지 않는다")
    void propagatesUnexpectedProgrammingError() {
        RestClient restClient = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    throw new IllegalStateException("unexpected programming error");
                })
                .build();
        SocialProfileImageHttpDownloader downloader = downloader(restClient, 1024);

        assertThatThrownBy(() -> downloader.download(
                ProviderType.KAKAO,
                "https://k.kakaocdn.net/profile.png"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected programming error");
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
        return downloader(restClient, maxSizeBytes, 0);
    }

    private SocialProfileImageHttpDownloader downloader(RestClient restClient, long maxSizeBytes, int maxRedirects) {
        SocialProfileImageDownloadProperties properties = new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                maxSizeBytes,
                maxRedirects
        );
        return new SocialProfileImageHttpDownloader(
                restClient,
                properties,
                new SocialProfileImageUriPolicy()
        );
    }
}
