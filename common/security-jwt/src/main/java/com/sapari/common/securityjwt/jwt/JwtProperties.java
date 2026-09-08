package com.sapari.common.securityjwt.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 서명·만료 설정.
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String secret,
        @NotNull @Positive Long accessTokenExpirationSeconds,
        @NotNull @Positive Long refreshTokenExpirationSeconds
) {
    /** HMAC 최소 길이. HS256 의 블록 크기(256bit)보다 짧은 키는 보안 강도가 그만큼 깎인다. */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * 길이 검사를 {@code @Size} 가 아니라 여기서 한다. <b>이게 이 클래스의 핵심이다.</b>
     *
     * <p>{@code @Size} 위반은 record 의 {@code toString()} 을 타지 않는다 — 필드 단위
     * {@code FieldError} 로 보고되고 그 포맷이 <b>{@code rejected value [...]} 로 값을 그대로
     * 싣는다</b>(실측). 즉 짧은 실제 키를 넣으면 마스킹과 무관하게 원문이 로그에 남는다. 형제인
     * {@code LiveKitProperties}·{@code RoomTokenProperties} 에 이 경로가 없는 건 거기엔 값 제약이
     * {@code @NotBlank} 뿐이라 거절값이 빈 문자열이기 때문이지, 마스킹이 막아줘서가 아니다.
     *
     * <p>컴팩트 생성자에서 던지면 메시지를 우리가 쓰므로 값이 실리지 않는다. <b>길이나 형식을 검증하는
     * 제약을 비밀값 필드에 달지 말 것</b> — 거절값이 곧 비밀이다.
     */
    public JwtProperties {
        if (secret != null && secret.length() < MIN_SECRET_LENGTH) {
            // 길이도 실으면 안 된다 — 키 공간을 좁혀 준다.
            throw new IllegalArgumentException(
                    "jwt.secret 이 너무 짧습니다 — 최소 " + MIN_SECRET_LENGTH + "자가 필요합니다."
                            + " (값은 로그에 남기지 않습니다)");
        }
    }

    /**
     * {@code secret} 은 HMAC 서명키 — 이 값이면 <b>임의 사용자의 토큰을 위조</b>할 수 있다.
     * record 기본 {@code toString()} 은 원문을 그대로 내보낸다. 위 컴팩트 생성자가 유출 경로를 닫지만,
     * {@code /actuator/configprops}·디버그 로그처럼 객체를 그냥 문자열화하는 자리가 따로 있어
     * 마스킹도 함께 둔다({@code LiveKitProperties} 와 같은 규칙).
     */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", secret=***"
                + ", accessTokenExpirationSeconds=" + accessTokenExpirationSeconds
                + ", refreshTokenExpirationSeconds=" + refreshTokenExpirationSeconds + "]";
    }
}
