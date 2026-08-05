package com.sapari.customer.infrastructure.external;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 소셜 프로필 이미지 다운로드의 timeout, 크기와 redirect 제한 정책이다. */
@ConfigurationProperties(prefix = "sapari.customer.social-profile-image.download")
public record SocialProfileImageDownloadProperties(
        Duration connectTimeout,
        Duration readTimeout,
        long maxSizeBytes,
        int maxRedirects
) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(3);
    private static final long DEFAULT_MAX_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final int DEFAULT_MAX_REDIRECTS = 1;

    /** 설정 누락이나 잘못된 범위 값을 운영 안전 기본값으로 정규화한다. */
    public SocialProfileImageDownloadProperties {
        if (connectTimeout == null) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
        if (maxRedirects < 0) {
            maxRedirects = DEFAULT_MAX_REDIRECTS;
        }
    }
}
