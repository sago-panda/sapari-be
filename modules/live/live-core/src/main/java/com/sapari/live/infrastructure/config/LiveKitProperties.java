package com.sapari.live.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("livekit")
public record LiveKitProperties(
        String host,
        String apiKey,
        String apiSecret,
        S3 s3,
        Hls hls
) {
    public record S3(
            String bucket, String region,
            String keyPrefix,
            String accessKey, String secretKey
    ) {}

    public record Hls(String cdnBaseUrl, int segmentDuration) {}
}