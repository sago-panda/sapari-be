package com.sapari.liveapp.config;

import java.security.PrivateKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.common.securityjwt.jwt.JwtKeys;
import com.sapari.common.securityjwt.jwt.JwtProperties;

/**
 * live-app(클라우드)이 홈랩에서 통하는 사용자 토큰을 만들 수 있는 키를 갖지 않도록 기동 시 막는다(SPR-210).
 *
 * <p>사용자 JWT 를 RS256 으로 바꾼 이유가 "클라우드 비밀만으로 사용자 토큰(ADMIN 포함)을 위조할 수 없게" 다.
 * 그 전제가 설정 한 줄로 깨지는 길이 둘이고, 둘 다 기동은 정상이라 아무 신호가 없다:
 * <ul>
 *   <li>{@code jwt.signing-key} 를 넣는다 — 이 앱은 발급하지 않으므로 둘 이유가 없다.</li>
 *   <li>룸 토큰 개인키로 사용자 토큰 키쌍을 재사용한다 — 둘 다 RSA 라 넣을 수 있게 됐다.</li>
 * </ul>
 * 예외 메시지에 키 원문을 싣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class LiveJwtKeyGuardConfig {

    @Bean
    public LiveJwtKeyGuard liveJwtKeysMustBeVerifyOnly(JwtProperties jwtProperties, PrivateKey roomTokenPrivateKey) {
        if (jwtProperties.signingKey() != null) {
            throw new IllegalStateException(
                    "live-app 에 jwt.signing-key 가 있다 — 사용자 토큰 서명 개인키는 api-app 에만 둔다. 이 앱은 "
                            + "jwt.verification-keys(공개키)만으로 검증한다.");
        }
        if (JwtKeys.from(jwtProperties).hasVerificationKeyFor(roomTokenPrivateKey)) {
            throw new IllegalStateException(
                    "live.room-token.private-key 가 jwt.verification-keys 의 공개키와 같은 키쌍이다 — 룸 토큰과 "
                            + "사용자 토큰은 별도 RSA 키쌍을 쓴다(같으면 이 앱의 비밀로 사용자 토큰을 위조할 수 있다).");
        }
        return new LiveJwtKeyGuard();
    }

    /** 위 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class LiveJwtKeyGuard {
    }
}
