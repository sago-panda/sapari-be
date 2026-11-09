package com.sapari.live.infrastructure.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 썸네일 업로드 허가 서명 설정. 정적 키는 egress 와 같은 값을 쓰되({@code S3_*} 환경변수) 설정은 분리한다 —
 * {@link LiveKitProperties.S3} 와 수명이 묶이지 않고, 썸네일 전용 버킷·IAM 사용자로 나누기 쉽다.
 *
 * @param cdnBaseUrl 검증 완료 썸네일을 노출하는 CDN 주소(https). 방 썸네일 주소 판정의 기준이다.
 */
@Validated
@ConfigurationProperties("live.thumbnail")
public record ThumbnailProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String cdnBaseUrl
) {
    @Override
    public String toString() {
        return "ThumbnailProperties[bucket=" + bucket + ", region=" + region + ", cdnBaseUrl=" + cdnBaseUrl
                + ", accessKey=***, secretKey=***]";
    }
}
