package com.sapari.customer.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SocialProfileImageDownloadPropertiesTest {

    @Test
    void zeroOrMissingTimeoutBecomesDefaultBecauseZeroMeansUnlimited() {
        SocialProfileImageDownloadProperties properties =
                new SocialProfileImageDownloadProperties(Duration.ZERO, null, 0, -1);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.maxSizeBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(properties.maxRedirects()).isEqualTo(1);
    }

    @Test
    void acceptsValuesAtUpperBound() {
        SocialProfileImageDownloadProperties properties = new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), 5L * 1024 * 1024, 3);

        assertThat(properties.maxRedirects()).isEqualTo(3);
    }

    @Test
    void rejectsValuesAboveUpperBound() {
        assertThatThrownBy(() -> new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(6), Duration.ofSeconds(3), 1024, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(6), 1024, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(3), 5L * 1024 * 1024 + 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SocialProfileImageDownloadProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(3), 1024, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
