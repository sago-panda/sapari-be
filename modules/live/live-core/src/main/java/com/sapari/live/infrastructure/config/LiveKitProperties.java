package com.sapari.live.infrastructure.config;

import jakarta.validation.Valid;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.sapari.global.validator.UrlValidator;

/**
 * LiveKit 접속 설정.
 *
 * <p>{@code host} 는 <b>루프백이 아니면 https 여야 한다</b> — {@code startHlsEgress} 가 매 호출마다 S3
 * accessKey/secretKey 를 요청 본문에 실어 보내고, API 인증 JWT 도 같은 연결로 나간다. 평문 http 면
 * 그 자격증명이 그대로 노출된다. 로컬 개발(127.0.0.1/localhost)만 예외로 두어 별도 플래그 없이도
 * 개발은 되고 운영 오설정은 부팅에서 막힌다.
 */
@Validated
@ConfigurationProperties("livekit")
public record LiveKitProperties(
        @NotBlank String host,
        @NotBlank String apiKey,
        @NotBlank String apiSecret,
        @NotNull @Valid S3 s3,
        @NotNull @Valid Hls hls
) {
    /**
     * apiSecret 은 자격증명 — 임의 방에 publish 할 토큰 발급 + webhook 서명 위조가 가능하다.
     * 위 컴팩트 생성자가 던지면 "Failed to bind properties under 'livekit'" 로그에 이 객체가 실리므로
     * 마스킹이 없으면 부팅 실패 한 번으로 유출된다.
     */
    @Override
    public String toString() {
        return "LiveKitProperties[host=" + host + ", apiKey=" + apiKey
                + ", apiSecret=***, s3=" + s3 + ", hls=" + hls + "]";
    }

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public LiveKitProperties {
        requireSecureUrl(host, "livekit.host");
    }

    /** 루프백이 아니면 https 를 강제한다. 대소문자는 무시한다 — {@code http://LOCALHOST} 도 로컬이다. */
    static void requireSecureUrl(String url, String key) {
        if (url == null || url.isBlank()) {
            return; // @NotBlank 가 보고하도록 둔다 — 여기서 던지면 바인딩 오류 메시지가 묻힌다
        }
        UrlValidator.validateHttpUrl(url);
        if (url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return;
        }
        String hostName = URI.create(url).getHost();
        if (hostName == null || !LOOPBACK_HOSTS.contains(hostName.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    key + " 는 https 여야 합니다(루프백 제외) — 자격증명·재생 URL 이 평문으로 나갑니다: " + url);
        }
    }

    /**
     * accessKey·secretKey 는 자격증명 — record 기본 {@code toString()} 은 원문을 그대로 노출한다.
     * 부모만 마스킹하면 {@code s3()} 를 문자열화할 때 우회되므로 여기서도 오버라이드한다
     * ({@code RoomTokenProperties} 와 동일 규칙).
     */
    public record S3(
            @NotBlank String bucket,
            @NotBlank String region,
            @NotBlank String keyPrefix,
            @NotBlank String accessKey,
            @NotBlank String secretKey
    ) {
        @Override
        public String toString() {
            return "S3[bucket=" + bucket + ", region=" + region + ", keyPrefix=" + keyPrefix
                    + ", accessKey=***, secretKey=***]";
        }
    }

    /**
     * @param cdnBaseUrl 시청자에게 그대로 나가는 재생 URL 의 접두사라 {@code host} 보다 더 엄격해야 한다 —
     *                   루프백이 아니면 https 강제.
     */
    public record Hls(
            @NotBlank String cdnBaseUrl,
            @Positive int segmentDuration
    ) {
        public Hls {
            requireSecureUrl(cdnBaseUrl, "livekit.hls.cdn-base-url");
        }
    }
}