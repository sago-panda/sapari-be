package com.sapari.live.application.port;

/**
 * 정리 잡이 회차를 통째로 접은 이유.
 *
 * <p><b>이 지표가 이 설계의 핵심이다.</b> 회차를 접는 코드 경로는 로그만 남기고 조용히 끝나므로,
 * 밖에서 보면 "정리할 게 없어 평화로운 회차" 와 구분되지 않는다. LiveKit host/apiKey 오설정은 전송
 * 실패가 아니라 {@code 200 + []} 로 오기 때문에 정확히 이 모습이고, 그동안 좀비 방송은 아무도 치우지
 * 않은 채 쌓인다. 이 카운터가 유일한 신호다.
 */
public enum ReconcileAbortReason {
    /**
     * 후보는 있는데 클러스터 전체 활성 egress 가 0건 — LiveKit 오설정 의심.
     *
     * <p>값이 하나뿐이라도 enum 으로 두는 건, 중단 사유가 늘 때 새 시계열이 아니라 태그 값 하나가
     * 늘어나게 하기 위해서다. 그리고 여기에 값을 추가할 자격은 <b>회차 전체를 접는 경로</b>에만
     * 있다 — 방 단위 판정은 {@code ReconcileAction} 쪽이다.
     */
    NO_ACTIVE_EGRESS_CLUSTER_WIDE
}
