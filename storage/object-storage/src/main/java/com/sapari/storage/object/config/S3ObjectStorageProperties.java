package com.sapari.storage.object.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * S3-compatible object storage에 접속하고 기본 bucket/public URL을 정하기 위한 공통 인프라 설정이다.
 * 도메인 모듈은 object key만 결정하고, bucket과 공개 기준 URL은 storage 설정을 따른다.
 */
@Validated
@ConfigurationProperties(prefix = "sapari.storage.object.s3")
public record S3ObjectStorageProperties(
        @NotBlank String endpoint,
        @NotBlank String region,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        boolean pathStyleAccessEnabled,
        @NotBlank String bucket,
        @NotBlank String publicBaseUrl
) {
    public S3ObjectStorageProperties {
        // 형식이 틀린 기준 URL 은 기동 때 막는다. 통과시키면 프로필 이미지가 있는 사용자 조회가 매번 500 이 된다.
        // 빈 값은 @NotBlank 가 같은 기동 단계에서 거부한다.
        if (publicBaseUrl != null && !publicBaseUrl.isBlank() && !isAbsoluteHttpUrl(publicBaseUrl)) {
            throw new IllegalArgumentException("sapari.storage.object.s3.public-base-url 은 http(s) 절대 URL 이어야 합니다.");
        }
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            // 소문자만 받는다. 조회 때 쓰는 UrlValidator 가 대소문자를 구분하므로, 여기서 대문자를 통과시키면 기동은 되고 조회가 실패한다.
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 자격증명이 로그에 원문으로 남지 않게 가린다(actuator configprops 는 toString 을 쓰지 않으니 별도). */
    @Override
    public String toString() {
        return "S3ObjectStorageProperties[endpoint=" + endpoint
                + ", region=" + region
                + ", accessKey=***"
                + ", secretKey=***"
                + ", pathStyleAccessEnabled=" + pathStyleAccessEnabled
                + ", bucket=" + bucket
                + ", publicBaseUrl=" + publicBaseUrl
                + "]";
    }
}
