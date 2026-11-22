package com.sapari.customer.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("소셜 프로필 이미지 URI 정책 테스트")
class SocialProfileImageUriPolicyTest {

    private final SocialProfileImageUriPolicy policy = new SocialProfileImageUriPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://image.example/profile.png",
            "http://image.example/profile.png",
            "http://image.example:80/profile.png",
            "https://image.example:443/profile.png"
    })
    @DisplayName("HTTP와 HTTPS의 표준 포트 URL을 허용한다")
    void allowsHttpAndHttpsWithStandardPorts(String value) {
        assertThat(policy.isAllowed(URI.create(value))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://image.example/profile.png",
            "gopher://image.example/profile.png",
            "http://image.example:443/profile.png",
            "https://image.example:80/profile.png",
            "https://image.example:8080/profile.png",
            "http://image.example:6379/profile.png",
            "/profile.png",
            "mailto:user@example.com",
            "https://user:password@image.example/profile.png"
    })
    @DisplayName("금지 scheme과 비표준 포트, 상대 URI, host 없음, userinfo를 거부한다")
    void rejectsInvalidUriStructure(String value) {
        assertThat(policy.isAllowed(URI.create(value))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/profile.png",
            "http://[::1]/profile.png",
            "http://[::ffff:127.0.0.1]/profile.png",
            "http://2130706433/profile.png",
            "http://0x7f000001/profile.png",
            "http://0177.0.0.1/profile.png",
            "http://0x7f.0.0.1/profile.png",
            "http://0x7f.0x0.0x0.0x1/profile.png",
            "http://%31%32%37.0.0.1/profile.png"
    })
    @DisplayName("IP literal과 숫자형 IP 표기를 거부한다")
    void rejectsIpLiteralsAndNumericIpForms(String value) {
        assertThat(policy.isAllowed(URI.create(value))).isFalse();
    }

    @Test
    @DisplayName("정책 길이 상한을 넘는 URL을 거부한다")
    void rejectsOverlongUri() {
        URI uri = URI.create("https://image.example/" + "a".repeat(2048));

        assertThat(policy.isAllowed(uri)).isFalse();
    }
}
