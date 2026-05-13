package com.sapari.live.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("livekit")
public record LiveKitProperties(
        @NotBlank String host,
        @NotBlank String apiKey,
        @NotBlank String apiSecret,
        @NotNull @Valid S3 s3,
        @NotNull @Valid Hls hls
) {
    public record S3(
            @NotNull String bucket,
            @NotNull String region,
            @NotNull String keyPrefix,
            @NotNull String accessKey,
            @NotNull String secretKey
    ) {}

    public record Hls(@NotNull @Pattern(regexp = "^http?://.*") String cdnBaseUrl,
                      @NotNull int segmentDuration
    ) {}
}