package com.sapari.common.securityjwt.store;

/**
 * Refresh Token 회전(CAS) 결과.
 */
public enum RefreshTokenRotationResult {

    /** 제시한 jti 가 현재 jti 라 새 jti 로 교체했다. */
    ROTATED,

    /**
     * 제시한 jti 가 방금 교체된 직전 jti 이고 유예 시간 안이다 — 탭 두 개의 동시 재발급 같은 정상 경합으로 본다.
     * 세션을 폐기하지 않고 회전도 하지 않는다.
     */
    REUSED_WITHIN_GRACE,

    /** 세션이 없거나(로그아웃·만료) 유예 밖의 옛 jti 다 — 재사용(탈취)으로 본다. */
    REJECTED
}
