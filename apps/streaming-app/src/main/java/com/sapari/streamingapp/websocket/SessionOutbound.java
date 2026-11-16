package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.springframework.web.reactive.socket.CloseStatus;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.DeliveryStage;
import com.sapari.chat.application.port.SessionCutReason;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks;

/**
 * 세션 채널 송신부 — 데이터 채널(Sink)에 넣기, 종료(데이터 + 제어 채널), 경합 스핀 재시도와 그 예산.
 *
 * <p>{@link ChatSessionRegistry} 가 세션 색인으로 대상을 고르고, 고른 세션에 무엇을 어떻게 넣는지는 여기서
 * 정한다. 이 클래스의 메서드는 전부 <b>동기로 끝난다</b> — 스핀은 절대 시각 예산으로 끊기고 종료 신호는
 * 논블로킹이다. 포트({@code ChatSessionManager})의 팬아웃 완료 계약이 이 성질에 기댄다.
 */
@Slf4j
final class SessionOutbound {

    /**
     * 동시 emit 경합 재시도 상한(벽시계 아님 — 스핀 시간 측정이라 TimeProvider 대상이 아니다).
     *
     * <p>락 보유 구간은 큐 적재 + 드레인(직렬화)이라 정상 상태엔 수십 µs 수준이고 5ms는 두 자릿수 여유다.
     * 여기 닿는 건 경합이 길어서가 아니라 <b>보유 스레드가 느려지거나 선점될 때</b>다 — 기동 직후 첫 드레인,
     * STW GC, CPU 스로틀링. 셋 다 JVM 전역이라 여러 세션이 동시에 걸린다. 그래서 상한을 짧게 두어
     * 이벤트루프 점유를 줄이되, 소진했다고 세션을 끊지는 않는다(emit 주석 참고).
     *
     * <p>⚠️ <b>그 여유는 emit 한 건 기준이고, 이 값은 emit마다 주어지지 않는다.</b> 방 fan-out은 한
     * 연산이 예산 하나를 나눠 쓰므로(deadline은 절대 시각이다 — {@code emitSerially} 참고) 실질 여유가
     * 방 세션 수에 비례해 줄어든다. 실측으로 60세션 fan-out 1회가 0.100ms(이 값의 2%)인데 12,000세션은
     * 3.96ms(79%)다. 이 값을 읽고 "두 자릿수 여유가 있으니 예산 소비자를 더 붙여도 된다"로 가면,
     * 큰 방에서 꼬리 세션부터 재시도 없이 한 번만 시도하게 된다.
     */
    static final long DEFAULT_CONTENTION_SPIN_NANOS = Duration.ofMillis(5).toNanos();

    /**
     * 종료 신호 재시도 상한. emit보다 넉넉히 주는 이유는 종료 보장이 아니라 <b>전달 확률</b>이다 —
     * 이 신호가 걸려야 버퍼 잔여분이 흘러나가고, 강퇴 직전에 보낸 SYSTEM(KICKED)이 클라에 닿는다.
     * 놓쳐도 제어 채널이 종료 자체는 책임진다({@code terminate} 참고).
     */
    static final long DEFAULT_COMPLETE_SPIN_NANOS = Duration.ofMillis(50).toNanos();

    /** 관측 포트 — 드롭과 버퍼 초과 종료를 센다. */
    private final ChatMetrics metrics;

    /**
     * emit 경합 재시도 상한. 운영은 {@link #DEFAULT_CONTENTION_SPIN_NANOS}.
     *
     * <p>상수가 아니라 필드인 이유는 <b>테스트가 이 값을 넓혀야 하기 때문</b>이다. 이 값에 기대는
     * 테스트들이 고정하는 것은 절대값이 아니라 <b>관계</b>다 — "예산보다 짧은 경합은 넘겨진다",
     * "예산보다 긴 경합은 버려지고 계수된다". 상수로 두면 그 관계를 세울 유일한 레버가 체류 시간이고,
     * 그러면 마진이 ms 자릿수에 갇힌다(1ms 체류 vs 5ms 예산 = 5배). 스레드가 수십 ms 선점되는 것은
     * 부하에서 흔한 일이라 그 마진은 지워지고, 테스트는 <b>설계가 약속하지 않은 것</b>을 요구하다
     * 흔들린다(경합 소진 시 드롭은 best-effort 계약이다).
     *
     * <p>예산과 체류를 <b>같이</b> 키우면 관계가 글자 그대로 보존되면서 마진만 곱해진다. 기본값에서
     * 동작은 완전히 동일하다 — 행동 변화가 0 인 진입점이다.
     *
     * <p>⚠️ 결정론은 아니다. 벽시계는 여전히 벽시계이고, 필요한 선점이 ms 에서 수백 ms 로 옮겨가
     * 확률이 내려가는 것이다. 시계를 가짜로 바꾸는 쪽은 안 된다 — {@code Thread.onSpinWait()} 루프가
     * 가짜 시계로는 돌지 않아 재려던 기제 자체가 사라진다. <b>키우되 가짜로 만들지 않는다.</b>
     */
    private final long contentionSpinNanos;

    /** 종료 신호 재시도 상한. 운영은 {@link #DEFAULT_COMPLETE_SPIN_NANOS}. 위와 같은 이유로 필드다. */
    private final long completeSpinNanos;

    /** 경합 재시도 소진으로 버린 메시지 수. 유실을 조용히 넘기지 않기 위한 카운터. */
    private final AtomicLong droppedOnContention = new AtomicLong();

    /** 만든 fan-out 공유 예산 수 — fan-out당 하나라는 불변식을 테스트가 고정한다({@link #budgetsCreated}). */
    private final LongAdder budgetsCreated = new LongAdder();

    /** 경합 드롭 로그 솎아내기. */
    private final LogThrottle contentionDropLog = new LogThrottle();

    SessionOutbound(ChatMetrics metrics, long contentionSpinNanos, long completeSpinNanos) {
        this.metrics = metrics;
        this.contentionSpinNanos = contentionSpinNanos;
        this.completeSpinNanos = completeSpinNanos;
    }

    long contentionSpinNanos() {
        return contentionSpinNanos;
    }

    long completeSpinNanos() {
        return completeSpinNanos;
    }

    /**
     * 세션 Sink로 1건 밀어넣기. null 세션은 무시.
     *
     * <p>실패 종류에 따라 처분이 다르다. 버퍼가 찼다는 건 <b>그 클라가 못 따라온다</b>는 뜻이라 세션을 끊고,
     * 경합 소진은 <b>다른 스레드가 그 순간 락을 쥐고 있었다</b>는 뜻일 뿐 세션은 멀쩡하므로 그 메시지만 버린다.
     * 둘을 같이 묶으면 부하가 오를수록(=소진이 잦아질수록) 멀쩡한 시청자를 끊게 되고, 끊긴 클라의 재접속이
     * 부하를 더 올려 스스로 번진다.
     *
     * <p>아웃바운드 종료 신호는 버퍼에 쌓인 메시지 뒤에 줄을 서므로 소켓을 읽지 않는 클라에겐 닿지 않는다.
     * 그 경우를 위해 {@code terminate}가 별도 제어 채널을 함께 발화한다(핸들러가 그걸로 연결을 끊는다).
     */
    void emit(LocalSession ls, OutboundFrame frame, FanOutBudget budget) {
        if (ls == null) {
            return;
        }
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitNext(frame), budget.emitDeadline());
        if (consumerCannotKeepUp(result)) {
            // 1013(SERVICE_OVERLOAD) — 정상 종료(1000)로 닫으면 프론트가 곧장 다시 붙는다. 버퍼가 넘친 건
            // 그 클라가 못 따라온다는 뜻이라, 즉시 재접속은 같은 결과를 부르고 부하 상황일수록 스스로 번진다.
            // 재시도 자체는 의미가 있으므로 금지(1008)가 아니라 "나중에"로 알린다 — 간격은 프론트 backoff가 정한다.
            log.warn("아웃바운드 버퍼 초과 — 세션 종료 sessionId={} roomId={} userId={} result={}",
                    ls.sessionId(), ls.session().roomId(), ls.session().userId(), result);
            // complete에 별도 예산을 주지 않는다(이 fan-out의 emitDeadline을 그대로 넘긴다). complete는 "버퍼에 남은 걸
            // 흘려보낸 뒤 곱게 닫는" 경로인데, 여기 온 이유가 바로 그 버퍼가 꽉 차서 클라가 안 읽는다는
            // 것이라 흘려보낼 곳이 없다. 그 무의미한 일에 최대 45ms를 쓰면 — 그것도 이 Pod의 모든 방을
            // 중계하는 Redis 구독 스레드 위에서 — fan-out 예산이 그만큼 지나가 뒤쪽 세션들이 재시도
            // 없이 한 번만 시도하게 된다. 종료 자체는 제어 채널이 보장하므로 잃는 것도 없다.
            metrics.sessionCut(SessionCutReason.BUFFER_OVERFLOW);
            terminate(ls, CloseStatus.SERVICE_OVERLOAD, budget.emitDeadline());
        } else if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            // 세션은 살려둔다. 대신 드롭을 세어 "조용한 유실"이 되지 않게 한다.
            // 로그는 솎아낸다 — 드롭이 나는 상황이 곧 CPU 포화라, 건당 동기 로그가 그걸 더 악화시킨다.
            metrics.deliveryDropped(DeliveryStage.SESSION_EMIT_CONTENTION);
            long dropped = droppedOnContention.incrementAndGet();
            if (contentionDropLog.tryAcquire()) {
                // id는 이 창을 연 한 건의 것이고 누적은 Pod 전역이다 — "이 세션이 N건"으로 읽히지 않게 표기한다
                log.warn("경합 재시도 소진 — 메시지 드롭(세션 유지) 표본 sessionId={} 표본 roomId={} 누적드롭={}",
                        ls.sessionId(), ls.session().roomId(), dropped);
            }
        }
    }

    /** 단건 전송용 — 이 emit 하나가 예산을 독차지한다. */
    void emit(LocalSession ls, OutboundFrame frame) {
        emit(ls, frame, newBudget());
    }

    /**
     * 한 연산(fan-out 1회 또는 단건 전송)이 나눠 쓰는 예산.
     *
     * <p>emit과 종료를 따로 잡는 이유: 종료 신호는 버퍼 잔여분을 최대한 흘려보내야 강퇴 직전에 보낸
     * SYSTEM(KICKED)이 전달되므로 더 넉넉히 준다. 둘 다 <b>연산 시작 시각 기준</b>이라 세션 수만큼
     * 곱해지지 않는다 — 방 fan-out은 방 전체 중계를 담당하는 Redis 구독 스레드 위에서 돈다.
     */
    record FanOutBudget(long emitDeadline, long completeDeadline) {
    }

    /**
     * 세션 종료 — 사유를 기록하고, 데이터 채널(complete)과 제어 채널 양쪽에 알린다.
     *
     * <p>둘 다 거는 이유: complete는 버퍼에 남은 메시지를 먼저 흘려보낸 뒤 곱게 닫는 정상 경로다
     * (강퇴 직전에 보낸 SYSTEM(KICKED)이 이 덕에 전달된다). 제어 채널은 그게 막혔을 때 —
     * 클라가 소켓을 읽지 않아 버퍼가 비지 않을 때 — 자원을 회수하는 확실한 경로다.
     */
    void terminate(LocalSession ls, CloseStatus status, long deadline) {
        // 이미 종료 중이면 그때 정해진 사유를 유지한다 — 제어 채널(Sinks.One)도 첫 값만 받으므로,
        // 저장된 사유와 실제로 흘러간 사유가 어긋나지 않게 여기서 한 번만 확정한다.
        //
        // 뒤늦은 강퇴가 앞선 종료 사유를 덮지 못한다는 뜻이기도 하다(버퍼 초과 직후 강퇴가 오면 1013으로 닫힌다).
        // 덮게 만들려면 제어 채널을 다시 발화해야 하는데 Sinks.One은 못 하고, 억지로 하면 위 불변식이 깨진다.
        // 실질 피해도 없다 — 어느 코드로 닫히든 재접속은 입장 게이트의 강퇴 조회에서 다시 막힌다.
        if (!ls.closeStatus().compareAndSet(null, status)) {
            return;
        }
        complete(ls, deadline);
        // 제어 채널은 데이터 채널이 막혔을 때의 최후 경로다 — 실패하면 그 세션은 강제 종료 수단을 잃으므로 남긴다.
        //
        // ⚠️ <b>아래 실패 분기는 현재 코드에서 도달 불가다.</b> Sinks.one()은 SinkOneSerialized 이고 첫
        // tryEmitValue 는 구독자가 없어도, 구독자가 취소한 뒤에도 OK 를 돌려준다(실측). 실패는 2회차
        // (FAIL_TERMINATED)와 동시 발화(FAIL_NON_SERIALIZED)뿐인데, 위 closeStatus CAS 가 발화를
        // 1회·1스레드로 고정하고 이 메서드가 유일한 호출지다. 지우지 않는 이유는 그 두 전제(CAS 와
        // 단일 호출지) 중 하나만 깨져도 곧 도달하게 되고, 그때 잃는 것이 "그 세션의 강제 종료 수단"이라
        // 조용히 넘어가서는 안 되기 때문이다. <b>이 로그가 보이면 그 전제를 먼저 확인할 것.</b>
        Sinks.EmitResult signalled = ls.terminate().tryEmitValue(status);
        if (signalled.isFailure()) {
            log.error("세션 종료 제어 신호 발화 실패 — 강제 종료 경로 상실 sessionId={} roomId={} result={}",
                    ls.sessionId(), ls.session().roomId(), signalled);
        }
    }

    /** 세션 하나를 위한 예산 — 단건 전송과 단건 종료가 쓴다. 세지 않는다({@link #newSharedBudget} 참고). */
    FanOutBudget newBudget() {
        long now = System.nanoTime();
        return new FanOutBudget(now + contentionSpinNanos, now + completeSpinNanos);
    }

    /**
     * 한 번의 fan-out이 <b>세션들과 나눠 쓸</b> 예산. 방·계정 단위로 여럿에게 닿는 모든 경로가 이것을 쓴다.
     *
     * <p>{@link #newBudget}과 만드는 값이 같은데 따로 둔 이유는 <b>세기 때문</b>이다. 그리고 세는 이유는
     * 이것이 관측 지표가 아니라 불변식이라서다 — 호출마다 새 예산을 주는 쪽으로 되돌려도 드롭은
     * <b>줄거나 같으므로</b> 결과를 보는 단언으로는 구별되지 않는다. 그 회귀를 잡는 수단이 inbox 크기와
     * 드롭 카운터뿐일 때는 원리적으로 관측이 안 되고, 재시도 상한이 세션 수에 비례해 곱해지는 것을
     * (그것도 방 전체 중계를 담당하는 Redis 구독 스레드 위에서) 아무도 알아채지 못한다.
     *
     * <p><b>단건 경로를 여기 섞지 마라.</b> 한때 셈이 {@link #newBudget} 안에 있었는데, 그 경로는
     * 메시지마다 불리므로 모든 ACK·유니캐스트가 테스트만 읽는 카운터를 위해 경합 CAS를 하나씩 물었다.
     * 불변식은 "fan-out 하나가 예산 하나"이고 단건은 그 불변식의 대상이 아니다.
     *
     * <p>⚠️ <b>이 갈라짐이 스스로를 지키지는 못한다.</b> 기존 경로가 {@link #newBudget}으로 돌아가면
     * 그 경로를 덮는 단언이 깨지지만, <b>일곱 번째 경로를 새로 만들고 열거 테스트에 한 줄 적지 않으면
     * 아무도 묻지 않는다</b> — 조건절이 자기 자신이다. 구조로 막으려면 {@code LocalSessionIndex.forEachInRoom}·
     * {@code forEachSessionOf}가 예산을 만들어 넘기게 해서 fan-out 측에 per-session 예산을 만들 수단
     * 자체를 없애야 하고, 그러면 이 셈도 필요 없어진다. 오늘의 여섯 열거는 완전하다.
     */
    FanOutBudget newSharedBudget() {
        budgetsCreated.increment();
        return newBudget();
    }

    /**
     * 만든 fan-out 공유 예산의 누적 수. <b>관측용이 아니라 불변식 고정용이다</b>(위 참고).
     *
     * <p>{@code LongAdder}인 것은 <b>지금 근거가 약하다.</b> 셈을 단건 경로에서 뺀 뒤로는 스레드 간
     * 쓰기 경합이 사실상 없고, {@code LongAdder}가 이기는 조건은 쓰기/읽기 비율이 아니라 그 경합이다 —
     * {@code AtomicLong}으로도 족하다(한때 이 자리에 "쓰기가 읽기보다 많아서"라고 적혀 있었는데
     * 조건을 잘못 짚은 것이다). 비용이 중립이라 두지만, 이 선택을 근거로 확장하지 말 것.
     *
     * (테스트 진입점)
     */
    long budgetsCreated() {
        return budgetsCreated.sum();
    }

    /**
     * 버퍼에 자리가 없어 밀어넣지 못한 경우 — 그 클라가 소비를 못 따라온다는 신호라 세션을 끊는다.
     * {@code FAIL_ZERO_SUBSCRIBER}도 "구독 전"이 아니라 "구독 전에 버퍼가 찼다"는 뜻이라 같이 묶는다.
     * 이미 끝난 세션({@code FAIL_TERMINATED}/{@code FAIL_CANCELLED})과 일시 경합
     * ({@code FAIL_NON_SERIALIZED})은 해당하지 않는다. (정책을 한 곳에 모아 테스트로 고정하기 위한 진입점)
     */
    boolean consumerCannotKeepUp(Sinks.EmitResult result) {
        return result == Sinks.EmitResult.FAIL_OVERFLOW
                || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER;
    }

    /** 경합으로 버린 메시지 누적 수. (관측용 — 0이 아니면 그만큼 화면이 어긋났다는 뜻) */
    long droppedOnContention() {
        return droppedOnContention.get();
    }


    /**
     * 데이터 채널 쪽 종료 — 버퍼에 남은 걸 흘려보낸 뒤 곱게 닫는 정상 경로다.
     * 경합으로 놓쳐도 {@code terminate}의 제어 채널이 종료를 보장하므로, 여기 실패는 치명적이지 않다.
     */
    private void complete(LocalSession ls, long deadline) {
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitComplete(), deadline);
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            log.warn("데이터 채널 종료 실패(경합 소진) — 제어 채널로 종료한다 sessionId={} roomId={}",
                    ls.sessionId(), ls.session().roomId());
        }
    }

    /**
     * Sink는 동시 emit을 직렬화하지 않는다 — 경합하면 {@code FAIL_NON_SERIALIZED}를 돌려주고 값을 버린다.
     * 한 세션에 ack(WS 이벤트루프)와 방 브로드캐스트(Redis 구독 스레드)가 동시에 들어오므로, Reactor 권장대로
     * 경합 구간만 짧게 busy-loop 재시도한다.
     *
     * <p>상한을 짧게 잡는 이유: 락을 쥔 쪽은 버퍼를 비우는 동안(직렬화까지) 쥐고 있고, 기다리는 쪽은
     * <b>Netty 이벤트루프에서 spin</b>할 수 있다 — 그 루프에 붙은 다른 커넥션까지 함께 멈춘다.
     * 소진 시 처분은 호출자가 정한다.
     *
     * <p>예산은 <b>절대 시각(deadline)</b>으로 받는다. 방 fan-out은 세션 수만큼 이 메서드를 부르는데,
     * 호출마다 새 예산을 주면 상한이 세션 수에 비례해 곱해진다 — 그것도 방 전체 중계를 담당하는
     * Redis 구독 스레드 위에서. 그래서 한 번의 fan-out이 예산 하나를 나눠 쓴다.
     */
    private Sinks.EmitResult emitSerially(Supplier<Sinks.EmitResult> emitter, long deadline) {
        while (true) {
            Sinks.EmitResult result = emitter.get();
            if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED || System.nanoTime() - deadline >= 0) {
                return result;
            }
            Thread.onSpinWait();
        }
    }
}
