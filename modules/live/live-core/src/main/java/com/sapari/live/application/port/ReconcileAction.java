package com.sapari.live.application.port;

/**
 * 정리 잡이 후보 하나에 실제로 한 일.
 *
 * <p>합계가 아니라 갈래별로 세는 이유: "정리 완료 12건" 은 12건을 종료한 회차와 12건을 그냥 넘긴
 * 회차를 구분하지 못한다. 사용자에게 나타나는 증상이 정반대인데 지표가 같으면 관측의 의미가 없다.
 */
public enum ReconcileAction {
    /** Ready 방을 뒤늦게 Live 로 승격 */
    PROMOTED,
    /** Ready 방을 만료 종료 */
    EXPIRED,
    /** 방치된 Live 방을 종료 */
    ENDED,
    /** 송출이 살아 있어 손대지 않음(정상 방송) */
    SPARED,
    /** 이미 처리됐거나 판정 불가라 건너뜀 */
    SKIPPED,
    /**
     * DB 는 ingress 배정을 아는데 LiveKit 이 그 방의 ingress 를 모름 — 오설정 의심으로 건너뜀.
     *
     * <p>일반 {@link #SKIPPED} 에서 갈라놓은 이유: 그쪽에는 "이미 판매자가 종료한 방" 처럼 정상적인
     * 스킵이 섞여 늘 0 이 아니다. 섞으면 오설정 신호가 정상 잡음에 묻힌다. 반대로 회차 중단
     * (aborted) 으로 세서도 안 된다 — 이 판정은 <b>방마다</b> 하므로 회차 하나에서 후보 수만큼
     * 오르고, 그러면 "aborted + completed = 회차 수" 가 깨져 회차 지표 전체가 못 쓰게 된다.
     */
    SKIPPED_INGRESS_MISSING,
    /**
     * 방별 egress 조회 실패로 종료 판정을 미룸.
     * 정상 상태 경합인 {@link #SKIPPED} 와 분리해 LiveKit 장애가 정상 잡음에 묻히지 않게 한다.
     */
    SKIPPED_EGRESS_LOOKUP_FAILED,
    /** 전역 스냅샷에는 활성이었으나 직전 방별 목록이 비어 판정 불일치로 미룸 */
    SKIPPED_EGRESS_SNAPSHOT_MISMATCH,
    /**
     * 고아 ingress 삭제를 <b>요청</b>했다.
     *
     * <p>아래 셋은 이름이 전부 {@code _REQUESTED} 다. 정리 포트는 결과를 돌려주지 않기 때문이다 —
     * {@code deleteIngress}/{@code stopHlsEgress}/{@code closeRoom} 은 실패를 삼키도록 <b>의도적으로</b>
     * 설계돼 있고(AGENTS "Cleanup swallows"), 그래야 정리 실패가 방송 종료를 막지 않는다. 즉 호출자는
     * 성공 여부를 알 수 없으므로 "지웠다" 로 세면 그건 지표가 아니라 추측이다.
     *
     * <p><b>그럼 실제로 지워졌는지는 어떻게 아나</b> — 다음 회차가 답한다. 이 잡은 매 회차 LiveKit 을
     * 전수 조회하므로, 안 지워졌으면 같은 건이 다시 잡힌다. <b>같은 수치가 회차마다 반복되면 "치우는 중"이
     * 아니라 "안 치워지는 중"</b>이다. 이름이 {@code DELETED} 였다면 그 반복이 정상으로 읽힌다.
     */
    INGRESS_DELETE_REQUESTED,
    /** 고아 egress 중단을 요청했다(방 단위). 위 주석 참고. */
    EGRESS_STOP_REQUESTED,
    /** 고아 SFU 방 닫기를 요청했다. 위 주석 참고. */
    SFU_ROOM_CLOSE_REQUESTED
}
