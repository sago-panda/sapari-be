package com.sapari.seller.infrastructure.external;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seller.business-registration.nts")
public record NtsBusinessRegistrationProperties(
        String baseUrl,
        String serviceKey,
        Duration connectTimeout,
        Duration readTimeout
) {

    private static final String DEFAULT_BASE_URL = "https://api.odcloud.kr/api/nts-businessman/v1";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    public NtsBusinessRegistrationProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (connectTimeout == null) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
    }

    @Override
    public String toString() {
        return "NtsBusinessRegistrationProperties[baseUrl=" + baseUrl
                + ", serviceKey=***"
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + "]";
    }
}
