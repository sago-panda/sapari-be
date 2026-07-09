package com.sapari.user.infrastructure.storage;

import lombok.RequiredArgsConstructor;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.sapari.global.validator.UrlValidator;
import com.sapari.storage.object.config.S3ObjectStorageProperties;
import com.sapari.user.application.port.ProfileImageUrlResolver;

/**
 * DB에 저장된 프로필 이미지 object key를 클라이언트 응답용 공개 URL로 변환한다.
 * 저장소에는 key만 남기고, API 응답을 만들 때 공통 storage의 publicBaseUrl을 붙여 profileImageUrl을 만든다.
 */
@Component
@RequiredArgsConstructor
public class ProfileImagePublicUrlResolver implements ProfileImageUrlResolver {

    private final S3ObjectStorageProperties properties;

    /**
     * 저장된 object key 앞에 publicBaseUrl을 붙여 공개 URL을 만든다.
     * key가 없으면 기본 이미지 URL은 API/클라이언트 정책에 맡기고 null을 반환한다.
     * 결과 URL은 MinIO, S3Mock, CDN 주소를 모두 허용하기 위해 AWS S3 전용 형식이 아닌 일반 HTTP URL로 검증한다.
     */
    @Override
    public String resolve(String profileImageKey) {
        if (profileImageKey == null || profileImageKey.isBlank()) {
            // 기본 이미지 정책은 API/클라이언트가 결정하므로 resolver가 임의 URL을 만들지 않는다.
            return null;
        }
        validateObjectKey(profileImageKey);
        // 공개 URL은 CDN/MinIO/S3Mock 중 어디든 될 수 있으므로 AWS S3 URL 형식으로 제한하지 않는다.
        String url = trimTrailingSlash(properties.publicBaseUrl()) + "/" + trimLeadingSlash(profileImageKey);
        UrlValidator.validateHttpUrl(url);
        return url;
    }

    private void validateObjectKey(String profileImageKey) {
        String normalized = profileImageKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            throw new IllegalArgumentException("profileImageKey must be an object key, not a URL");
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String trimLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }
}
