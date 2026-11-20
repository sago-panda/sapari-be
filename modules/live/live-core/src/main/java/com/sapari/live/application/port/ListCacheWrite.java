package com.sapari.live.application.port;

/**
 * 공개 목록 캐시에 대한 쓰기 종류 — 실패 지표의 태그 축이다.
 *
 * <p>열거인 이유는 형제 {@link ReconcileJob} 과 같다: 세는 쪽과 읽는 쪽이 같은 값을 쓰게 한다.
 */
public enum ListCacheWrite {
    /** Live 전이 직후 목록에 넣기. 실패하면 방송 중인데 목록에 없다. */
    REGISTER,
    /** 종료 전이 직후 목록에서 빼기. 실패하면 끝난 방송이 목록에 남는다. */
    REMOVE
}
