package com.sapari.live.application.port;

/**
 * 입장 상한의 집단 — 버킷이 둘이고 지표도 그 축으로 갈린다.
 *
 * <p>열거인 이유: 거부를 세는 쪽과 그 수를 읽는 쪽이 같은 값을 쓰게 하고, 집단이 하나 늘면
 * 태그 문자열이 조용히 갈리는 대신 컴파일이 깨지게 하는 것이다(형제 {@code ReconcileJob} 과
 * 같은 이유).
 */
public enum EnterPopulation {
    /** 미인증 시청자. */
    GUEST,
    /** 인증 회원 — 가입이 셀프서비스라 이 축에도 상한이 필요하다. */
    MEMBER
}
