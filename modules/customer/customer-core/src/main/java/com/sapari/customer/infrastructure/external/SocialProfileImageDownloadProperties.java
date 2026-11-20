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

    // 상한: 이 다운로드는 가입 요청 스레드에서, 가입 처리 lock(SocialSignupAttemptProperties.lockTtl)을 쥔 채 돈다.
    // 상한은 lock 초과를 줄일 뿐 보장하지 않는다 — read timeout 은 읽기 한 번의 상한이고 redirect 마다 다시 적용된다.
    // lock 이 먼저 풀려도 중복 가입은 users 유니크 인덱스가 막는다. 크기는 ImageFileValidator 의 5MiB 를 넘을 이유가 없다.
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 3;

    /** 설정 누락이나 0 이하 값은 운영 안전 기본값으로 정규화하고, 상한을 넘는 값은 기동 때 거부한다. */
    public SocialProfileImageDownloadProperties {
        // 0 은 HTTP 클라이언트에서 '무제한' 이다(Timeout.of(Duration.ZERO).isDisabled()). 기본값으로 바꾼다.
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
        if (maxRedirects < 0) {
            maxRedirects = DEFAULT_MAX_REDIRECTS;
        }
        if (connectTimeout.compareTo(MAX_TIMEOUT) > 0 || readTimeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("소셜 프로필 이미지 다운로드 timeout 은 5초 이하여야 합니다.");
        }
        if (maxSizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("소셜 프로필 이미지 최대 크기는 5MiB 이하여야 합니다.");
        }
        if (maxRedirects > MAX_REDIRECTS) {
            throw new IllegalArgumentException("소셜 프로필 이미지 redirect 는 3회 이하여야 합니다.");
        }
    }
}
