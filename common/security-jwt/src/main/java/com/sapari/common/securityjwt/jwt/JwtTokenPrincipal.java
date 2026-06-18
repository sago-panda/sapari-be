package com.sapari.common.securityjwt.jwt;

import java.util.UUID;

/**
 * JWT 발급에 필요한 사용자 snapshot.
 * common 모듈이 UserView 같은 도메인 API 타입에 의존하지 않도록 필요한 값만 받는다.
 */
public record JwtTokenPrincipal(
        UUID userId,
        String role,
        String nickname,
        String email
) {
}
