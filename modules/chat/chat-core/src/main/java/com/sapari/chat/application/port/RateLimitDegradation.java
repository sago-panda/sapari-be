package com.sapari.chat.application.port;

/**
 * 레이트리밋이 완전한 판정을 못 하고 내려앉은 모양. <b>둘 다 집행은 계속된다</b> — 정확도만 잃는다.
 */
public enum RateLimitDegradation {

    /**
     * Pod 로컬 창으로 강등. 크로스 Pod 정확도를 잃어 허용량이 Pod 수만큼 늘어난다
     * (창당 Pod 수 × 1). 통째로 사라지는 것보다 낫다는 판단이고, 그 판단이 맞았는지는 이 수치로 본다.
     */
    LOCAL_WINDOW,

    /**
     * 거부는 확정됐는데 남은 시간을 못 읽어 창 전체를 되돌려 줬다. 거부 자체는 지켜지고
     * {@code retryAfterSeconds} 만 실제보다 길어진다.
     */
    TTL_FALLBACK
}
