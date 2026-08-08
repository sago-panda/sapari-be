package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * host·cdnBaseUrl 은 루프백이 아니면 https 여야 한다 — 전자는 S3 자격증명을 실어 보내는 연결이고,
 * 후자는 시청자에게 그대로 나가는 재생 URL 이다.
 *
 * <p>제약이 두 곳에 나뉘어 있어 <b>둘 다</b> 검증한다: 컴팩트 생성자는 {@code new} 로 잡히지만
 * {@code @NotBlank}/{@code @Positive} 는 Validator 를 돌려야 보인다. 생성자만 보면
 * 애노테이션 제약이 깨진 채로 초록이 뜬다.
 */
class LiveKitPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static final String API_SECRET = "lk-api-secret-value";
    private static final String S3_ACCESS_KEY = "s3-access-key-value";
    private static final String S3_SECRET_KEY = "s3-secret-key-value";

    private static LiveKitProperties props(String host, String cdnBaseUrl, int segmentDuration) {
        return new LiveKitProperties(host, "key", API_SECRET,
                new LiveKitProperties.S3("bucket", "ap-northeast-2", "live/", S3_ACCESS_KEY, S3_SECRET_KEY),
                new LiveKitProperties.Hls(cdnBaseUrl, segmentDuration));
    }

    private static LiveKitProperties valid() {
        return props("https://livekit.example.com", "https://cdn.example.com", 2);
    }

    // ---------- https 강제 ----------

    @Test
    @DisplayName("https host·cdn 은 통과한다 — 애노테이션 제약까지 함께 확인")
    void allowsHttps() {
        assertThatCode(LiveKitPropertiesTest::valid).doesNotThrowAnyException();
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    @DisplayName("루프백은 http 도 허용한다 — 별도 플래그 없이 로컬 개발이 되도록. 대소문자 무시")
    void allowsPlainHttpOnLoopback() {
        assertThatCode(() -> props("http://localhost:7880", "http://localhost:8080/live", 2))
                .doesNotThrowAnyException();
        assertThatCode(() -> props("http://127.0.0.1:7880", "http://[::1]:8080/live", 2))
                .doesNotThrowAnyException();
        assertThatCode(() -> props("http://LOCALHOST:7880", "https://cdn.example.com", 2))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("루프백이 아닌 http host 는 막는다")
    void rejectsPlainHttpHost() {
        assertThatThrownBy(() -> props("http://livekit.example.com", "https://cdn.example.com", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("livekit.host");
    }

    @Test
    @DisplayName("cdnBaseUrl 도 같은 규칙 — 시청자에게 나가는 URL 이라 오히려 더 엄격해야 한다")
    void rejectsPlainHttpCdn() {
        assertThatThrownBy(() -> props("https://livekit.example.com", "http://cdn.example.com", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cdn-base-url");
    }

    @Test
    @DisplayName("URL 형식이 아니면 막는다 — 'htt://' 같은 오타가 통과하던 정규식 회귀 방지")
    void rejectsMalformedUrl() {
        assertThatThrownBy(() -> props("htt://livekit.example.com", "https://cdn.example.com", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props("https://livekit.example.com", "htt://cdn.example.com", 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Bean Validation ----------

    @Test
    @DisplayName("segmentDuration 0·음수는 제약 위반 — primitive 에 @NotNull 은 항상 통과하는 no-op 이었다")
    void rejectsNonPositiveSegmentDuration() {
        assertThat(validator.validate(props("https://livekit.example.com", "https://cdn.example.com", 0)))
                .isNotEmpty();
        assertThat(validator.validate(props("https://livekit.example.com", "https://cdn.example.com", -1)))
                .isNotEmpty();
    }

    @Test
    @DisplayName("S3 자격증명이 비면 제약 위반")
    void rejectsBlankS3Credentials() {
        LiveKitProperties blank = new LiveKitProperties("https://livekit.example.com", "key", "secret",
                new LiveKitProperties.S3("bucket", "ap-northeast-2", "live/", " ", ""),
                new LiveKitProperties.Hls("https://cdn.example.com", 2));

        assertThat(validator.validate(blank)).isNotEmpty();
    }

    // ---------- 자격증명 마스킹 ----------

    @Test
    @DisplayName("toString 은 자격증명을 가린다 — 바인딩 실패 로그에 이 객체가 실린다")
    void toStringMasksCredentials() {
        LiveKitProperties p = valid();

        assertThat(p.toString()).doesNotContain(API_SECRET).contains("apiSecret=***");
        // 중첩 record 는 따로 마스킹해야 한다 — s3() 를 문자열화하면 부모 마스킹을 우회한다
        assertThat(p.s3().toString()).doesNotContain(S3_ACCESS_KEY).doesNotContain(S3_SECRET_KEY)
                .contains("accessKey=***").contains("secretKey=***");
    }
}
