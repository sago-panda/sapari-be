package com.sapari.live.application.port;

/**
 * 정리 잡 3종의 식별자. 지표 태그로 쓴다.
 *
 * <p>문자열이 아니라 enum 인 이유: 태그 오타는 예외 없이 <b>새 시계열</b>을 만들고, 그 시계열은 어떤
 * 대시보드에도 잡히지 않는다. 잘못된 값이 조용히 통계에서 사라지는 실패라 컴파일러가 막게 한다.
 */
public enum ReconcileJob {
    EXPIRE_READY,
    END_STALE_LIVE,
    ORPHAN_MEDIA
}
