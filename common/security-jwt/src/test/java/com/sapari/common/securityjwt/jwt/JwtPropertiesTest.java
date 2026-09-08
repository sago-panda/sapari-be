package com.sapari.common.securityjwt.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    /**
     * 이 값이면 임의 사용자의 토큰을 위조할 수 있다. 바인딩 검증이 걸리면 Spring 이 이 객체를
     * 로그에 그대로 싣기 때문에, 마스킹이 없으면 부팅 실패 한 번으로 평문이 남는다.
     */
    /**
     * 실제 유출 경로는 {@code toString()} 이 아니었다 — {@code @Size} 위반은 필드 단위 FieldError 로
     * 보고되고 그 포맷이 {@code rejected value [...]} 로 값을 그대로 싣는다. 그래서 길이 검사를
     * 컴팩트 생성자로 옮겼고, 이 테스트가 그 사실을 고정한다.
     */
    @Test
    @DisplayName("짧은 키를 거부하되 예외 메시지에 값을 싣지 않는다")
    void shortSecret_isRejectedWithoutEchoingTheValue() {
        String shortSecret = "REAL-HMAC-KEY-TOO-SHORT";

        assertThatThrownBy(() -> new JwtProperties("sapari", shortSecret, 900L, 1_209_600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret);
    }

    @Test
    @DisplayName("toString 은 HMAC 서명키를 노출하지 않는다")
    void toString_masksTheSigningSecret() {
        JwtProperties properties = new JwtProperties("sapari", SECRET, 900L, 1_209_600L);

        assertThat(properties.toString())
                .doesNotContain(SECRET)
                .contains("secret=***")
                .contains("issuer=sapari");
    }
}
