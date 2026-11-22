package com.sapari.live.infrastructure.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 썸네일 업로드 허가 서명 설정. 정적 키는 egress 와 <b>다른</b> 전용 IAM 사용자다({@code THUMBNAIL_S3_*}
 * 환경변수, SPR-207) — 그 사용자의 권한은 {@code uploads/thumbnails/*} 의 {@code PutObject} 하나뿐이다.
 * presigned POST 는 서명자의 권한을 그대로 물려받으므로, 여기에 egress 키를 넣으면 업로드가
 * {@code AccessDenied} 로 끝난다({@link LiveKitProperties.S3} 쪽은 {@code live/} prefix 전용).
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
