package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.AccountWithdrawingSource;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.SessionCutReason;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.domain.repository.ChatWithdrawalWriteRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 탈퇴 신청 핸들러 — 형제 {@code LiveRoomEndedHandlerTest}·{@code ChatAccountEventHandlerTest} 와
 * 같은 축을 잰다: 순서가 계약이고, 한 사건의 실패가 구독을 죽이지 못한다.
 */
@ExtendWith(MockitoExtension.class)
class AccountWithdrawingHandlerTest {

    @Mock
    private AccountWithdrawingSource source;

    @Mock
    private ChatSessionManager sessionManager;

    @Mock
    private ChatWithdrawalWriteRepository withdrawalWriteRepository;

    @Mock
    private ChatMetrics metrics;

    @InjectMocks
    private AccountWithdrawingHandler handler;

    private final UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /** 기본은 기록 성공 — 끊기의 축을 재는 칸이 기록을 따로 세우지 않아도 되게 한다. */
    @BeforeEach
    void recordSucceedsByDefault() {
        lenient().when(withdrawalWriteRepository.markWithdrawing(any())).thenReturn(Mono.empty());
    }

    /**
     * ⭐ 기록이 끊기보다 먼저 <b>실행</b>된다. 반대면 끊긴 직후의 재접속이 기록보다 먼저 입장 게이트에
     * 닿아 남은 룸 토큰으로 들어온다. 형제 칸과 같은 이유로 호출 순서가 아니라 실행 순서를 잰다.
     */
    @Test
    @DisplayName("⭐ 탈퇴를 기록한 뒤에 끊는다 — 먼저 끊으면 재접속이 기록보다 앞설 수 있다")
    void theRecordIsWrittenBeforeTheClose() {
        List<String> ran = new CopyOnWriteArrayList<>();
        given(source.withdrawing()).willReturn(Flux.just(userId));
        given(withdrawalWriteRepository.markWithdrawing(userId))
                .willReturn(Mono.fromRunnable(() -> ran.add("record")));
        given(sessionManager.sendToUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("reason")));
        given(sessionManager.closeUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("close")));

        handler.start();

        assertThat(ran).containsExactly("record", "reason", "close");
        then(metrics).should(never()).withdrawalRecordFailed();
    }

    @Test
    @DisplayName("기록이 실패해도 사유를 보내고 끊는다 — 기록 실패가 사유 전송까지 건너뛰게 하지 않는다")
    void theReasonAndCloseProceedEvenIfTheRecordFails() {
        List<String> ran = new CopyOnWriteArrayList<>();
        given(source.withdrawing()).willReturn(Flux.just(userId));
        given(withdrawalWriteRepository.markWithdrawing(userId))
                .willReturn(Mono.error(new RuntimeException("Redis 장애")));
        given(sessionManager.sendToUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("reason")));
        given(sessionManager.closeUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("close")));

        handler.start();

        // 뒤의 사유 전송 onErrorResume 이 기록 실패도 받아 주므로 "끊긴다" 만 재면 기록 쪽 복구를 지워도
        // 통과한다 — 그때 빠지는 것이 사유 프레임이라 그것까지 단언한다.
        assertThat(ran).containsExactly("reason", "close");
        then(metrics).should().withdrawalRecordFailed();
    }

    /**
     * ⚠️ <b>{@code inOrder} 로 재지 않는다 — 그것은 호출 순서이고 계약은 실행 순서다.</b>
     * {@code .then(close(...))} 의 인자가 조립 시점에 평가되므로 Mockito 가 보는 호출 순서는
     * <b>소스 순서</b>다. 그래서 조립 순서를 그대로 둔 채 실행만 뒤집는 변이
     * ({@code close.then(reason)})가 {@code inOrder} 를 만족시키며 통과했다 — 실측으로 확인했고,
     * 그때 javadoc 이 적은 결과(사유 프레임이 닫힌 sink 로 사라진다)가 그대로 일어난다.
     * 그래서 두 스텁이 <b>구독될 때</b> 이름을 적고 그 순서를 단언한다.
     */
    @Test
    @DisplayName("⭐ 탈퇴는 WITHDRAWN 사유로 끊는다 — MODERATION 으로 세면 제재율이 부풀고 그 수가 거짓이 된다")
    void aWithdrawalIsCutUnderItsOwnReason() {
        // given: 이 메서드의 운영 호출자는 둘이고(전수) 하나는 밴 집행, 하나는 이 탈퇴다.
        // SystemMessageCode.WITHDRAWING 의 javadoc 이 탈퇴가 제재와 달라야 한다는 것을 이미 적는다 —
        // 사유를 공유하면 사용자가 스스로 누른 탈퇴가 chat.session.cut{reason=moderation} 을 올린다.
        given(source.withdrawing()).willReturn(Flux.just(userId));
        given(sessionManager.sendToUserEverywhere(any(), any())).willReturn(Mono.empty());
        given(sessionManager.closeUserEverywhere(any(), any())).willReturn(Mono.empty());

        // when
        handler.start();

        // then
        then(sessionManager).should(times(1))
                .closeUserEverywhere(userId, SessionCutReason.WITHDRAWN);
        then(sessionManager).should(never())
                .closeUserEverywhere(userId, SessionCutReason.MODERATION);
    }

    @Test
    @DisplayName("사유를 먼저 보내고 그다음 끊는다 — 먼저 끊으면 사유 프레임이 조용히 사라진다")
    void theReasonIsSentBeforeTheClose() {
        List<String> ran = new CopyOnWriteArrayList<>();
        given(source.withdrawing()).willReturn(Flux.just(userId));
        given(sessionManager.sendToUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("reason")));
        given(sessionManager.closeUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> ran.add("close")));

        handler.start();

        assertThat(ran).as("실행 순서 — 호출 순서가 아니다").containsExactly("reason", "close");
        then(sessionManager).should(times(1))
                .sendToUserEverywhere(userId, OutboundMessage.system(SystemMessageCode.WITHDRAWING));
        // 한 번만 — 뒤쪽 호출이 순서 단언을 만족시켜 중복이 가려지는 면을 막는다.
        then(sessionManager).should(times(1)).closeUserEverywhere(userId, SessionCutReason.WITHDRAWN);
    }

    /**
     * ⚠️ <b>호출됐는지가 아니라 실행됐는지를 잰다.</b> {@code .then(close(...))} 의 인자는
     * <b>조립 시점에</b> 평가되므로, 업스트림이 에러여도 {@code closeUserEverywhere} 라는 메서드
     * 호출 자체는 일어나고 Mockito 가 그것을 기록한다. 처음에 {@code should(times(1))} 로만 단언했는데
     * 역변이(사유 전송 실패를 삼키는 {@code onErrorResume} 제거)가 <b>잡히지 않았다</b> — 그 변이는
     * 종료를 <i>구독되지 않게</i> 만들 뿐 호출은 남기기 때문이다. 그래서 스텁이 실행 여부를 기록한다.
     */
    @Test
    @DisplayName("사유 전송이 실패해도 끊는다 — 알림 하나 때문에 탈퇴자 세션이 남으면 안 된다")
    void theCloseProceedsEvenIfTheReasonFails() {
        AtomicBoolean closed = new AtomicBoolean();
        given(source.withdrawing()).willReturn(Flux.just(userId));
        given(sessionManager.sendToUserEverywhere(any(), any()))
                .willReturn(Mono.error(new RuntimeException("전송 실패")));
        given(sessionManager.closeUserEverywhere(any(), any()))
                .willReturn(Mono.fromRunnable(() -> closed.set(true)));

        handler.start();

        assertThat(closed).as("구독돼 실제로 돌았는가 — 호출만으로는 구분되지 않는다").isTrue();
        then(sessionManager).should(times(1)).closeUserEverywhere(userId, SessionCutReason.WITHDRAWN);
    }

    @Test
    @DisplayName("한 사건이 실패해도 다음 탈퇴는 처리된다 — 봉투 하나가 구독을 죽이면 안 된다")
    void theSubscriptionSurvivesOneFailure() {
        UUID broken = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        given(source.withdrawing()).willReturn(Flux.just(broken, userId));
        given(sessionManager.sendToUserEverywhere(any(), any())).willReturn(Mono.empty());
        AtomicBoolean secondClosed = new AtomicBoolean();
        given(sessionManager.closeUserEverywhere(broken, SessionCutReason.WITHDRAWN))
                .willReturn(Mono.error(new RuntimeException("종료 실패")));
        given(sessionManager.closeUserEverywhere(userId, SessionCutReason.WITHDRAWN))
                .willReturn(Mono.fromRunnable(() -> secondClosed.set(true)));

        handler.start();

        assertThat(secondClosed).as("두 번째 사건이 실제로 돌았는가").isTrue();
    }

    @Test
    @DisplayName("조립 시점에 터져도 구독이 살아남는다 — defer 로 감싸지 않으면 여기서 스트림이 죽는다")
    void theSubscriptionSurvivesAnAssemblyTimeThrow() {
        UUID broken = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        given(source.withdrawing()).willReturn(Flux.just(broken, userId));
        // 포트가 Mono 를 돌려주기 전에 동기 throw 하는 상황. defer 없이 조립하면 이 예외가
        // onErrorResume 을 지나쳐 구독 자체를 끝낸다.
        given(sessionManager.sendToUserEverywhere(
                broken, OutboundMessage.system(SystemMessageCode.WITHDRAWING)))
                .willThrow(new IllegalStateException("조립 중 폭발"));
        given(sessionManager.sendToUserEverywhere(
                userId, OutboundMessage.system(SystemMessageCode.WITHDRAWING)))
                .willReturn(Mono.empty());
        AtomicBoolean closed = new AtomicBoolean();
        given(sessionManager.closeUserEverywhere(userId, SessionCutReason.WITHDRAWN))
                .willReturn(Mono.fromRunnable(() -> closed.set(true)));

        handler.start();

        assertThat(closed).as("조립 시점 throw 뒤에도 뒤따르는 사건이 실제로 돌았는가").isTrue();
    }

    @Test
    @DisplayName("종료 시 구독을 실제로 해제한다 — Pod 가 내려가는데 스트림이 남으면 안 된다")
    void theStopDisposesTheSubscription() {
        // 취소가 업스트림까지 전파되는지 관측한다. 이걸 안 보면 stop() 본문을 비워도 통과한다.
        AtomicBoolean cancelled = new AtomicBoolean();
        given(source.withdrawing()).willReturn(Flux.<UUID>never().doOnCancel(() -> cancelled.set(true)));
        handler.start();

        handler.stop();

        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("stop 을 두 번 불러도, start 없이 불러도 터지지 않는다 — 컨텍스트 종료 경로가 그렇다")
    void theStopIsSafeTwiceAndWithoutStart() {
        handler.stop();                       // start 전 — 부팅 실패 후 종료 경로
        given(source.withdrawing()).willReturn(Flux.never());
        handler.start();
        handler.stop();
        handler.stop();                       // 중복 호출
    }
}
