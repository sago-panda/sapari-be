package com.sapari.seller.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NtsBusinessRegistrationPropertiesTest {

    @Test
    @DisplayName("toString은 NTS 서비스 키를 마스킹한다")
    void toString_masksServiceKey() {
        String serviceKey = "nts-service-key-secret";
        NtsBusinessRegistrationProperties properties = new NtsBusinessRegistrationProperties(
                "https://api.example.com", serviceKey, Duration.ofSeconds(3), Duration.ofSeconds(5));

        assertThat(properties.toString())
                .doesNotContain(serviceKey)
                .contains("serviceKey=***");
    }
}
