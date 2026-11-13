package com.sapari.user.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * user 도메인 이벤트를 다른 도메인에 알리는 송신 포트.
 *
 * <p><b>와이어 모양은 이쪽 것이다.</b> 구독자는 자기 어댑터로 흡수한다 — {@code live:room:ended} 가
 * 세운 모양이고, 모듈 규칙({@code X-core → Y-api ONLY}·{@code -core} 는 남이 의존하지 않는다)에서
 * 도메인 간 이벤트가 취할 수 있는 유일한 형태다. 구독자 쪽 타입을 여기서 알지 않는다.
 */
public interface UserEventPublisher {

    /**
     * 탈퇴 <b>신청</b>을 알린다 — 상태가 {@code WITHDRAWING} 으로 바뀐 시점이다.
     *
     * <p>하드 삭제(배치)가 아니라 신청 시점인 이유: 그 순간부터 계정이 활성이 아니고
     * ({@code User.isActive()} 는 {@code ACTIVE} 만 참) 유예가 시작된다. 구독자가 알아야 하는 때도
     * 그때다 — 삭제까지 기다리면 유예 기간 전체가 구멍으로 남는다.
     *
     * <p><b>반드시 커밋 뒤에 부른다.</b> 트랜잭션 안에서 부르면 롤백된 신청이 발행되고, 받는 쪽은
     * 멀쩡한 계정에 조치를 한다(형제 {@code EndLiveService} 가 같은 이유로 {@code afterCommit} 에
     * 건다). 호출자가 그 순서를 지킬 책임을 갖는다 — 이 인터페이스는 강제하지 못한다.
     *
     * @param userId      신청한 사람
     * @param requestedAt 신청 시각(유예 시작)
     */
    void publishWithdrawalRequested(UUID userId, Instant requestedAt);
}
