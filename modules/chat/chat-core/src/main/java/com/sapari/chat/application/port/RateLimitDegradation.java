package com.sapari.chat.application.port;

/**
 * 레이트리밋이 완전한 판정을 못 하고 내려앉은 모양. <b>집행은 계속된다</b> — 정확도만 잃는다.
 */
public enum RateLimitDegradation {

    /**
     * Pod 로컬 판정으로 강등. 크로스 Pod 정확도를 잃어 허용량이 Pod 수만큼 늘어난다.
     * 통째로 사라지는 것보다 낫다는 판단이고, 그 판단이 맞았는지는 이 수치로 본다.
     *
     * <p>태그 값은 {@code local_window} 로 남긴다 — 고정 창 시절의 이름이지만 대시보드·알림이 이
     * 문자열을 쓴다. 판정 방식이 바뀌어도 "Pod 로컬로 내려갔다" 는 뜻은 같다.
     */
    LOCAL_WINDOW
}
