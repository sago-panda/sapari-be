package com.sapari.common.securityjwt.store;

import java.time.Duration;
import java.util.UUID;

/**
 * Refresh Token 저장소 포트. 구현(Redis 등)은 별도 인프라 모듈(common/auth)이 제공한다.
 * 토큰/세션은 사용자 도메인이 아니라 공통 인증 관심사라 common 계층에 둔다.
 */
public interface RefreshTokenStore {

    /**
     * 로그인 세션의 현재 Refresh Token ID를 저장한다.
     */
    void save(UUID sessionId, UUID refreshTokenId, Duration ttl);

    /**
     * 현재 저장된 Refresh Token ID가 기대값과 같을 때 새 ID로 교체한다.
     */
    boolean rotate(UUID sessionId, UUID expectedRefreshTokenId, UUID newRefreshTokenId, Duration ttl);

    /**
     * 로그인 세션의 Refresh Token 정보를 삭제한다.
     */
    void deleteBySessionId(UUID sessionId);
}
