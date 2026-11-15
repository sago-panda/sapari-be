package com.sapari.live.domain.model;

import java.util.Set;

/**
 * 다시보기 처리 상태(live_rooms.vod_status). LiveKit egress 웹훅이 녹화 결과를 따라 앞으로만 옮긴다:
 * NONE → PROCESSING → READY | FAILED. READY·FAILED 는 끝 상태라 먼저 확정된 값이 남는다. 웹훅을 놓친 종료 방은
 * 복구 잡이 NONE/PROCESSING 에서 READY 로 올린다(RecoverStuckReplaysService) — 쓰는 곳은 이 둘뿐이다.
 * 다시보기는 READY 만 노출한다 — 방 상태의 {@link LiveStatus.Ready}(송출 대기)와 무관하다.
 */
public enum VodStatus {
    NONE, PROCESSING, READY, FAILED;

    /**
     * 이 상태로 올 수 있는 앞 단계. 갱신은 "현재 값이 이 집합에 있을 때만" 한다(조건부 UPDATE) — 중복·역순 알림은
     * 그 조건에서 걸러진다. 끝 상태가 NONE 에서도 오는 건 시작 알림이 egressId 커밋보다 먼저 와 놓칠 수 있어서다.
     */
    public Set<VodStatus> predecessors() {
        return switch (this) {
            case NONE -> Set.of();
            case PROCESSING -> Set.of(NONE);
            case READY, FAILED -> Set.of(NONE, PROCESSING);
        };
    }
}
