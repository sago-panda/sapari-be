package com.sapari.common.securityjwt.store;

import java.util.UUID;

public interface SessionRevocationChecker {

    /**
     * 로그인 세션이 폐기되어 더 이상 사용할 수 없는 상태인지 확인한다.
     */
    boolean isRevoked(UUID sessionId);
}
