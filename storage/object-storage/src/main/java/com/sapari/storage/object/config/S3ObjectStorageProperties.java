package com.sapari.storage.object.config;

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
}
