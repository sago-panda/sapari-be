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
     *
     * <p>{@link #SKIPPED} 에서 갈라놓은 이유는 {@link #SKIPPED_INGRESS_MISSING} 과 같다 — 그쪽에는
     * "이미 판매자가 종료한 방" 같은 정상 스킵이 섞여 늘 0 이 아니다. 섞으면 <b>방별 호출만 지속
     * 실패해 종료가 0건인 상태</b>가 평범한 루틴 스킵으로 보인다. 회차는 completed 로 남으므로
     * 이 갈래가 없으면 밖에서 구분할 방법이 없다.
     */
    SKIPPED_EGRESS_LOOKUP_FAILED,
    /** 전역 스냅샷에는 활성이었으나 직전 방별 목록이 비어 판정 불일치로 미룸 */
    SKIPPED_EGRESS_SNAPSHOT_MISMATCH,
    /**
     * 고아 ingress 삭제를 <b>요청</b>했다.
     *
     * <p>아래 셋은 이름이 전부 {@code _REQUESTED} 다. 정리 포트는 결과를 돌려주지 않기 때문이다 —
     * {@code deleteIngress}/{@code stopHlsEgress}/{@code stopEgress}/{@code closeRoom} 은 실패를 삼키도록 <b>의도적으로</b>
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
    SFU_ROOM_CLOSE_REQUESTED,
    /**
     * 이번 회차에 손댈 것이 있다고 판정된 <b>방</b> 수(고아 정리).
     *
     * <p>종류별로 나누지 않는다 — 이 잡은 방을 순회 단위로 삼고, 한 방의 ingress·egress·SFU 방을
     * 한자리에서 정해진 순서로 처리한다. 종류별 후보 수를 세던 시절에는 그 셋이 각자 상한을 갖는
     * 구조였고, 그래서 "같은 방의 ingress 는 밀렸는데 방은 닫힘" 이 가능했다.
     * 실제로 요청한 건수는 종류별 {@code *_REQUESTED} 가 계속 나눠서 센다.
     */
    ORPHAN_ROOM_CANDIDATE,
    /** end-stale-live 조회에서 관측한 후보 수(포화 판정용으로 batch-size+1까지). */
    STALE_LIVE_CANDIDATE,
    /** end-stale-live 후보가 이번 회차 상한을 넘었다. */
    STALE_LIVE_BATCH_SATURATED,
    /**
     * 회차가 <b>시간 예산</b>을 다 써서 남은 후보를 다음 회차로 넘겼다.
     *
     * <p>{@link #STALE_LIVE_BATCH_SATURATED} 같은 포화 신호와 섞지 않는 이유: 저쪽은 "후보가
     * batch-size 보다 많다" 이고 이쪽은 "LiveKit 왕복이 느려 리스 안에 못 끝낸다" 다. 처방이
     * 정반대다 — 섞으면 지연 문제를 batch-size 를 올려 고치려 하고, 그러면 회차만 더 길어진다.
     *
     * <p>잡 이름을 접두사로 달지 않는다. 이 사실은 <b>세 잡 모두</b>에 있고(정리 잡의 후보당 왕복
     * 수는 어느 잡에서도 고정이 아니다), 어느 잡인지는 {@code job} 태그가 이미 말한다. 접두사를
     * 달면 같은 개념이 잡 수만큼 늘어난다.
     */
    ROUND_BUDGET_EXHAUSTED

    // 고아 정리에는 개수 상한이 없어 포화 신호도 없다 — 정지 규칙은 ROUND_BUDGET_EXHAUSTED 하나다.
    // INGRESS_BATCH_SATURATED / EGRESS_BATCH_SATURATED / SFU_ROOM_BATCH_SATURATED 는 종류별 상한과
    // 함께 사라졌다. 상한은 회차 비용을 미리 계산할 수 있다는 전제 위에 있었는데 방당 리소스 수가
    // 설정으로 늘어 그 전제가 성립하지 않았고, 상한이 있는 한 "일부만 처리하고 방을 닫는" 조합을
    // 막을 수 없었다. 적체는 ORPHAN_ROOM_CANDIDATE 가 줄지 않는 것으로 드러난다.
}
