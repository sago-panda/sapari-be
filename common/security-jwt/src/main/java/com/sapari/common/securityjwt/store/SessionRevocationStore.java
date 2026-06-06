package com.sapari.common.securityjwt.store;

import java.util.UUID;

public interface SessionRevocationStore {

    /**
     * 로그인 세션을 폐기 상태로 등록한다.
     */
    void revoke(UUID sessionId);
}
