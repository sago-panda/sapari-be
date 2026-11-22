package com.sapari.productapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.common.securityjwt.jwt.JwtProperties;

/**
 * product-app 은 사용자 토큰을 발급하지 않으므로 서명 개인키({@code jwt.signing-key})를 갖고 뜨지 않는다(SPR-210).
 *
 * <p>넣어도 기동은 정상이라 아무 신호가 없고, 그러면 이 앱의 비밀만으로 사용자 토큰(ADMIN 포함)을 위조할 수 있다.
 * 예외 메시지에 키 원문을 싣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class ProductJwtKeyGuardConfig {

    @Bean
    public ProductJwtKeyGuard productJwtKeysMustBeVerifyOnly(JwtProperties jwtProperties) {
        if (jwtProperties.signingKey() != null) {
            throw new IllegalStateException(
                    "product-app 에 jwt.signing-key 가 있다 — 사용자 토큰 서명 개인키는 api-app 에만 둔다. 이 앱은 "
                            + "jwt.verification-keys(공개키)만으로 검증한다.");
        }
        return new ProductJwtKeyGuard();
    }

    /** 위 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ProductJwtKeyGuard {
    }
}
