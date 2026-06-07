package com.sapari.common.securityjwt.store;

import java.util.Optional;
import java.util.UUID;

/**
 * Refresh Token 저장소 포트. 구현(Redis 등)은 별도 인프라 모듈(common/auth)이 제공한다.
 * 토큰/세션은 사용자 도메인이 아니라 공통 인증 관심사라 common 계층에 둔다.
 */
public interface RefreshTokenStore {

    void save(UUID sessionId, String refreshToken);

    Optional<String> findBySessionId(UUID sessionId);

    void deleteBySessionId(UUID sessionId);
}
