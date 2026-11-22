package com.sapari.storage.object.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S3ObjectStoragePropertiesTest {

    @Test
    void toStringHidesCredentials() {
        S3ObjectStorageProperties properties = properties("https://cdn.example.com");

        assertThat(properties.toString())
                .doesNotContain("access-key-value", "secret-key-value")
                .contains("accessKey=***", "secretKey=***");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cdn.example.com", "ftp://cdn.example.com", "https://", "not a url", "HTTPS://cdn.example.com"})
    void rejectsPublicBaseUrlThatIsNotAbsoluteHttpUrl(String publicBaseUrl) {
        assertThatThrownBy(() -> properties(publicBaseUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-key-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://cdn.example.com", "http://localhost:9000/bucket"})
    void acceptsAbsoluteHttpUrl(String publicBaseUrl) {
        assertThat(properties(publicBaseUrl).publicBaseUrl()).isEqualTo(publicBaseUrl);
    }

    private S3ObjectStorageProperties properties(String publicBaseUrl) {
        return new S3ObjectStorageProperties(
                "http://localhost:9000", "ap-northeast-2", "access-key-value", "secret-key-value",
                true, "bucket", publicBaseUrl);
    }
}
