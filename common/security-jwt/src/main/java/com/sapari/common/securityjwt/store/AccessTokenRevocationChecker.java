package com.sapari.common.securityjwt.store;

import java.util.UUID;

public interface AccessTokenRevocationChecker {

    /**
     * Access Token jti가 로그아웃 또는 강제 만료 처리되어 더 이상 사용할 수 없는 상태인지 확인
     */
    boolean isRevoked(UUID tokenId);
}
