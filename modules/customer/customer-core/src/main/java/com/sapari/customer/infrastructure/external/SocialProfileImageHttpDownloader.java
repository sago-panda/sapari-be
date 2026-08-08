package com.sapari.customer.infrastructure.external;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.sapari.customer.application.dto.SocialProfileImageDownloadResult;
import com.sapari.customer.application.port.SocialProfileImageDownloader;
import com.sapari.global.validator.ImageFileValidator;
import com.sapari.global.validator.ImageFileValidator.ImageFileValidationException;
import com.sapari.user.model.ProviderType;

/**
 * 서버가 보관한 provider 이미지 URL을 제한적으로 내려받아 검증·재인코딩한다.
 * URL 거부나 provider 장애는 선택 이미지 실패로 취급해 빈 결과를 반환한다.
 */
@Slf4j
@Component
public class SocialProfileImageHttpDownloader implements SocialProfileImageDownloader {

    private final RestClient restClient;
    private final SocialProfileImageDownloadProperties properties;
    private final SocialProfileImageUriPolicy uriPolicy;

    /** 소셜 이미지 전용 client, 다운로드 제한, URI SSRF 정책을 결합한다. */
    public SocialProfileImageHttpDownloader(
            @Qualifier("socialProfileImageRestClient") RestClient restClient,
            SocialProfileImageDownloadProperties properties,
            SocialProfileImageUriPolicy uriPolicy
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.uriPolicy = uriPolicy;
    }

    /**
     * provider URL의 구조를 검증한 뒤 redirect와 응답 크기를 제한하며 이미지를 다운로드한다.
     * URL 구조·다운로드·파일 검증 실패는 선택 이미지 import 실패로 이어지도록 빈 결과로 반환한다.
     */
    @Override
    public Optional<SocialProfileImageDownloadResult> download(
            ProviderType provider,
            String providerProfileImageUrl
    ) {
        if (providerProfileImageUrl == null || providerProfileImageUrl.isBlank()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = URI.create(providerProfileImageUrl);
        } catch (IllegalArgumentException e) {
            log.info("Social profile image download failed. provider={}, reason={}", provider, e.getClass().getSimpleName());
            return Optional.empty();
        }

        try {
            return download(provider, uri, 0);
        } catch (RestClientException | ImageFileValidationException e) {
            log.info("Social profile image download failed. provider={}, reason={}", provider, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 최초 URL과 각 redirect URL에 같은 URI 정책을 적용한 뒤 허용 횟수 안에서 다운로드한다. */
    private Optional<SocialProfileImageDownloadResult> download(ProviderType provider, URI uri, int redirectCount) {
        if (!uriPolicy.isAllowed(uri)) {
            // 보안 거부 목적지는 URL·host·IP를 로그에 남기지 않고 선택 이미지 실패로만 처리한다.
            return Optional.empty();
        }

        return restClient.get()
                .uri(uri)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is3xxRedirection()) {
                        URI location = response.getHeaders().getLocation();
                        if (location == null || redirectCount >= properties.maxRedirects()) {
                            return Optional.empty();
                        }
                        URI redirectUri = uri.resolve(location);
                        // 최초 URL이 안전해도 Location은 새 목적지이므로 재귀 진입점에서 같은 정책을 다시 적용한다.
                        return download(provider, redirectUri, redirectCount + 1);
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        return Optional.empty();
                    }
                    long contentLength = response.getHeaders().getContentLength();
                    if (contentLength > properties.maxSizeBytes()) {
                        return Optional.empty();
                    }
                    // Content-Length 누락·위조에도 상한을 지키도록 실제 stream 바이트를 다시 제한한다.
                    byte[] content = readWithMaxSize(response.getBody(), properties.maxSizeBytes());
                    String contentType = response.getHeaders().getContentType() == null
                            ? null
                            : normalizedContentType(response.getHeaders().getContentType().toString());
                    // provider 응답도 직접 업로드와 동일하게 magic bytes·decode·해상도·재인코딩 검증을 거친다.
                    ImageFileValidator.ValidatedImageFile image = ImageFileValidator.validate(
                            filenameFrom(uri, contentType),
                            contentType,
                            content
                    );
                    return Optional.of(new SocialProfileImageDownloadResult(
                            image.normalizedExtension(),
                            image.contentType(),
                            image.content()
                    ));
                });
    }

    /** Content-Length를 신뢰하지 않고 streaming 중 실제 수신 바이트 수를 제한한다. */
    private byte[] readWithMaxSize(InputStream inputStream, long maxSizeBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > maxSizeBytes) {
                throw new IOException("social profile image download size exceeded");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    /** provider Content-Type을 우선하고, 지원 형식이 아니면 URL 확장자를 파일명 단서로 사용한다. */
    private String filenameFrom(URI uri, String contentType) {
        String contentTypeFilename = switch (normalizedContentType(contentType)) {
            case "image/jpeg" -> "social-profile-image.jpg";
            case "image/png" -> "social-profile-image.png";
            case "image/webp" -> "social-profile-image.webp";
            default -> null;
        };
        if (contentTypeFilename != null) {
            return contentTypeFilename;
        }

        String path = uri.getPath();
        String filename = "social-profile-image";
        if (path != null && !path.isBlank() && !path.endsWith("/")) {
            int lastSlashIndex = path.lastIndexOf('/');
            filename = lastSlashIndex < 0 ? path : path.substring(lastSlashIndex + 1);
        }
        return filename;
    }

    /** Content-Type의 선택 파라미터를 제외하고 비교 가능한 형태로 정규화한다. */
    private String normalizedContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameterIndex = contentType.indexOf(';');
        String mediaType = parameterIndex < 0 ? contentType : contentType.substring(0, parameterIndex);
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

}
