package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * {@link ChatSessionManager} 구현 — 이 Pod에 붙은 WS 세션의 로컬 명부 + Redis HASH(크로스 Pod 집계) 조율.
 *
 * <p><b>왜 transport(streaming-app)에 두나</b>: 실제 송신 채널을 쥐는 유일한 곳이라서. chat-core 포트는
 * reactor Sink·WS 채널을 모르고(레이어 누수 방지) 도메인 {@link ChatSession}만 다룬다. 그래서 채널(Sink)은
 * 이 구현체가 소유하고, 핸들러는 {@link #outbound(String)}로 아웃바운드 스트림을 받아 session.send()에 연결한다.
 *
 * <p><b>Sink</b>: 외부(이 레지스트리)에서 임의 시점에 값을 밀어넣는 통로. T10 구독자가 메시지를 받으면
 * {@code sendToSession}으로 해당 세션 Sink에 emit → 그 WS로 흘러나간다. unicast+버퍼라 구독(=session.send) 전
 * emit도 버퍼에 보관된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionRegistry implements ChatSessionManager {

    /** 세션당 아웃바운드 버퍼 상한. 방 하나가 초당 수십 건 × 몇 초 적체를 견디는 크기. */
    static final int OUTBOUND_BUFFER_SIZE = 256;

    /**
     * 동시 emit 경합 재시도 상한(벽시계 아님 — 스핀 시간 측정이라 TimeProvider 대상이 아니다).
     *
     * <p>락 보유 구간은 큐 적재 + 드레인(직렬화)이라 정상 상태엔 수십 µs 수준이고 5ms는 두 자릿수 여유다.
     * 여기 닿는 건 경합이 길어서가 아니라 <b>보유 스레드가 느려지거나 선점될 때</b>다 — 기동 직후 첫 드레인,
     * STW GC, CPU 스로틀링. 셋 다 JVM 전역이라 여러 세션이 동시에 걸린다. 그래서 상한을 짧게 두어
     * 이벤트루프 점유를 줄이되, 소진했다고 세션을 끊지는 않는다(emit 주석 참고).
     */
    private static final long CONTENTION_SPIN_NANOS = Duration.ofMillis(5).toNanos();

    /**
     * 종료 신호 재시도 상한. emit보다 넉넉히 주는 이유는 종료 보장이 아니라 <b>전달 확률</b>이다 —
     * 이 신호가 걸려야 버퍼 잔여분이 흘러나가고, 강퇴 직전에 보낸 SYSTEM(KICKED)이 클라에 닿는다.
     * 놓쳐도 제어 채널이 종료 자체는 책임진다({@code terminate} 참고).
     */
    private static final long COMPLETE_SPIN_NANOS = Duration.ofMillis(50).toNanos();

    /**
     * 드롭 로그 솎아내기 간격 — 드롭이 몰리는 상황이 곧 CPU 포화라 건당 로그가 상황을 악화시킨다.
     * <b>건수가 아니라 경과시간</b>으로 센다: 건수 기준은 카운터가 프로세스 생애 누적이라 두 번째 이후의
     * 짧은 버스트가 통째로 침묵한다 — 드롭은 실제 메시지 유실이라 "조용한 유실이 되지 않게"라는
     * 이 클래스의 목표와 정면으로 어긋난다.
     */
    private static final long DROP_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ChatSessionRepository sessionRepository;   // Redis HASH 어댑터(T6) — 크로스 Pod activeCount

    /** sessionId → (도메인 세션 + 아웃바운드 Sink). 로컬 메모리(이 Pod 한정). */
    private final Map<String, LocalSession> local = new ConcurrentHashMap<>();

    /**
     * roomId → 그 방의 sessionId 집합. {@link #local}의 보조 인덱스다.
     *
     * <p>방 fan-out이 Pod 전체 세션을 훑지 않게 한다 — 메시지 1건마다 전체 스캔이면 다른 방 세션 수에
     * 비례해 비용이 붙고, 그걸 이벤트루프에서 한다. 갱신은 {@code compute} 계열로만 해서 등록/해제가
     * 서로 끼어들지 않게 하고, 방이 비면 항목 자체를 지운다(죽은 방 누적 방지).
     */
    private final Map<UUID, Set<String>> roomSessions = new ConcurrentHashMap<>();

    /** 경합 재시도 소진으로 버린 메시지 수. 유실을 조용히 넘기지 않기 위한 카운터. */
    private final AtomicLong droppedOnContention = new AtomicLong();

    /** 마지막 드롭 로그 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastDropLogNanos = new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

    /**
     * 한 커넥션의 채널 묶음.
     *
     * <p><b>{@code sink}(데이터) 와 {@code terminate}(제어)를 나눈 이유</b>: 종료를 sink complete로만 알리면
     * 그 신호가 버퍼에 쌓인 메시지 <i>뒤에</i> 줄을 선다. 소켓을 읽지 않는 클라에겐 영영 닿지 않아 연결·세션
     * 항목·방 구독이 회수되지 않는다. 제어 신호는 데이터 큐를 타지 않아야 한다.
     *
     * <p>{@code closeStatus}는 종료 사유 — complete로 곱게 닫히든 제어 채널로 끊기든 같은 코드로 닫기 위해
     * 따로 보관한다(complete 신호엔 코드를 실을 수 없다).
     */
    private record LocalSession(String sessionId, ChatSession session, Sinks.Many<OutboundMessage> sink,
            Sinks.One<CloseStatus> terminate, AtomicReference<CloseStatus> closeStatus) {
    }

    @Override
    public Mono<Void> register(String sessionId, ChatSession session) {
        // 세션마다 unicast Sink 1개(연결당 아웃바운드 1개). onBackpressureBuffer: 구독 전 emit·일시 적체 보관.
        // 버퍼는 유계 — 무제한이면 소비하지 않는 클라 하나가 Pod 힙을 잠식한다(초과 처리는 emit 참고).
        local.put(sessionId, new LocalSession(sessionId, session,
                Sinks.many().unicast().onBackpressureBuffer(Queues.<OutboundMessage>get(OUTBOUND_BUFFER_SIZE).get()),
                Sinks.one(), new AtomicReference<>()));   // closeStatus는 종료가 정해질 때 채워진다(null=미정)
        // compute — 집합 생성과 추가를 한 원자 구간에 묶는다. computeIfAbsent 후 add로 나누면 그 사이
        // 마지막 퇴장이 빈 집합을 걷어내 방금 넣은 세션이 인덱스에서 사라질 수 있다.
        roomSessions.compute(session.roomId(), (room, sessionIds) -> {
            Set<String> ids = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            ids.add(sessionId);
            return ids;
        });
        // Redis 명부 등재 실패로 접속을 막지 않는다 — 이 HASH는 시청자 수 집계 전용이고, 메시지 전달·강퇴·
        // 종료는 전부 위 로컬 자료구조와 Pub/Sub으로 돈다. 강퇴 조회 실패에도 입장을 허용하는 정책과 같은 방향.
        //
        // 다만 이 관용이 실제로 값을 내는 건 <b>일시적·부분적</b> 실패일 때다. Redis가 통째로 죽으면 전달
        // 자체가 Pub/Sub이라 아무도 못 받고, 그때 남는 건 "저장은 되고 발신자만 ack 받는" 상태다 —
        // 강퇴·레이트리밋도 같이 열려 있어 그 창의 전송이 이력에 그대로 쌓인다. 그 구간을 막을지는
        // 전송 경로(publish 실패 처리)의 정책 문제라 여기서 결정하지 않는다.
        return sessionRepository.add(session.roomId(), sessionId, session.userId())
                .onErrorResume(e -> {
                    log.warn("세션 명부 등재 실패 — 접속은 진행(시청자 수만 부정확) roomId={} cause={}",
                            session.roomId(), e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /** transport 전용: 핸들러가 session.send()에 연결할 아웃바운드 스트림. (포트 아님 — chat-core는 안 씀) */
    public Flux<OutboundMessage> outbound(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls == null ? Flux.empty() : ls.sink().asFlux();
    }

    @Override
    public Mono<Void> unregister(UUID roomId, String sessionId) {
        local.remove(sessionId);
        // 방이 비면 항목까지 걷어낸다 — 남기면 방송이 끝난 방이 계속 쌓인다(인덱스 자체가 누수원이 됨).
        roomSessions.computeIfPresent(roomId, (room, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
        // 로컬 정리는 끝났고, Redis 필드는 아래 HDEL이 지운다 — 그게 정상 회수 경로다.
        // 실패하면 방 종료의 clearRoom이, 그마저 놓치면 키 TTL이 받는다(3단 폴백).
        //
        // 호출부가 subscribe()라 에러를 그냥 두면 Reactor가 스택트레이스째 ERROR로 찍는다 — Redis가
        // 흔들리면 세션 수만큼 쏟아져 정작 원인 로그를 묻는다. 짚을 수 있는 형태로 한 줄만 남긴다.
        return sessionRepository.remove(roomId, sessionId)
                .onErrorResume(e -> {
                    log.warn("세션 명부 제거 실패 — 시청자 수 일시 과다 roomId={} sessionId={} cause={}",
                            roomId, sessionId, e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Long> getActiveCount(UUID roomId) {
        return sessionRepository.count(roomId);   // HVALS distinct = 고유 유저 수
    }

    @Override
    public Mono<Void> sendToSession(String sessionId, OutboundMessage message) {
        return Mono.fromRunnable(() -> emit(local.get(sessionId), message));
    }

    @Override
    public Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message) {
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> emit(ls, message, budget));
        });
    }

    @Override
    public Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver) {
        // 세션별 차등 fan-out — resolver가 세션마다 메시지 생성(방주인 PII 게이팅·kick 분기). null이면 그 세션 skip.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> {
                OutboundMessage message = resolver.apply(ls.session());
                if (message != null) {
                    emit(ls, message, budget);
                }
            });
        });
    }

    @Override
    public Mono<Void> closeUser(UUID roomId, UUID userId) {
        // 강퇴는 정책상 종료라 1008 — 프론트가 "재접속하지 말 것"으로 읽는 코드다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> {
                if (ls.session().userId().equals(userId)) {
                    terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
                }
            });
        });
    }

    @Override
    public Mono<Void> closeAll(UUID roomId) {
        // 방 종료는 정상 종료(1000). 사유는 앞서 보낸 SYSTEM(ROOM_ENDED)이 전달한다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> terminate(ls, CloseStatus.NORMAL, budget.completeDeadline()));
        });
    }

    /** transport 전용: 핸들러가 강제 종료 경로에 연결할 제어 신호. 모르는 세션이면 영영 발화하지 않는다. */
    public Mono<CloseStatus> terminationSignal(String sessionId) {
        LocalSession ls = local.get(sessionId);
        // 빈 Mono를 주면 즉시 완료돼 멀쩡한 세션이 곧장 닫힌다 — never여야 한다.
        return ls == null ? Mono.never() : ls.terminate().asMono();
    }

    /**
     * transport 전용: 서버가 이미 이 세션의 종료를 확정했는지.
     *
     * <p>종료 확정 후에도 소켓이 닫히기 전까지 짧은 창이 남는다(정상 클라는 ms, 소켓을 읽지 않는 클라는
     * 유예 시간만큼). 그 창에 들어오는 전송을 막지 않으면 강퇴된 사용자가 계속 보낼 수 있다.
     */
    public boolean isTerminating(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls != null && ls.closeStatus().get() != null;
    }

    /** transport 전용: 이 세션을 어떤 코드로 닫을지. 서버가 정한 사유가 없으면(클라가 먼저 끊는 등) 정상 종료. */
    public CloseStatus closeStatusOf(String sessionId) {
        LocalSession ls = local.get(sessionId);
        CloseStatus status = ls == null ? null : ls.closeStatus().get();
        return status == null ? CloseStatus.NORMAL : status;
    }

    /**
     * 세션 종료 — 사유를 기록하고, 데이터 채널(complete)과 제어 채널 양쪽에 알린다.
     *
     * <p>둘 다 거는 이유: complete는 버퍼에 남은 메시지를 먼저 흘려보낸 뒤 곱게 닫는 정상 경로다
     * (강퇴 직전에 보낸 SYSTEM(KICKED)이 이 덕에 전달된다). 제어 채널은 그게 막혔을 때 —
     * 클라가 소켓을 읽지 않아 버퍼가 비지 않을 때 — 자원을 회수하는 확실한 경로다.
     */
    private void terminate(LocalSession ls, CloseStatus status, long deadline) {
        // 이미 종료 중이면 그때 정해진 사유를 유지한다 — 제어 채널(Sinks.One)도 첫 값만 받으므로,
        // 저장된 사유와 실제로 흘러간 사유가 어긋나지 않게 여기서 한 번만 확정한다.
        if (!ls.closeStatus().compareAndSet(null, status)) {
            return;
        }
        complete(ls, deadline);
        // 제어 채널은 데이터 채널이 막혔을 때의 최후 경로다 — 실패하면 그 세션은 강제 종료 수단을 잃으므로 남긴다.
        Sinks.EmitResult signalled = ls.terminate().tryEmitValue(status);
        if (signalled.isFailure()) {
            log.error("세션 종료 제어 신호 발화 실패 — 강제 종료 경로 상실 sessionId={} roomId={} result={}",
                    ls.sessionId(), ls.session().roomId(), signalled);
        }
    }

    /**
     * 방 인덱스를 타고 해당 방 세션만 순회한다. Pod 전체 스캔을 피하는 자리라 방 fan-out은 모두 여기를 통한다.
     *
     * <p>인덱스에는 있지만 {@link #local}에서 이미 빠진 세션은 건너뛴다 — 퇴장 중인 세션과 겹칠 수 있고,
     * 그 세션은 어차피 곧 닫힌다.
     */
    private void forEachInRoom(UUID roomId, Consumer<LocalSession> action) {
        Set<String> sessionIds = roomSessions.get(roomId);
        if (sessionIds == null) {
            return;
        }
        for (String sessionId : sessionIds) {
            LocalSession ls = local.get(sessionId);
            if (ls != null) {
                action.accept(ls);
            }
        }
    }

    /** 인덱스가 추적 중인 방 수. (테스트 진입점 — 방이 비면 항목이 사라지는지 확인용) */
    int trackedRoomCount() {
        return roomSessions.size();
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
    private void emit(LocalSession ls, OutboundMessage message, FanOutBudget budget) {
        if (ls == null) {
            return;
        }
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitNext(message), budget.emitDeadline());
        if (consumerCannotKeepUp(result)) {
            // 코드는 1000 유지 — overflow 종료는 아직 프론트 계약에 없는 신규 케이스라 코드를 선점하지 않는다.
            log.warn("아웃바운드 버퍼 초과 — 세션 종료 sessionId={} roomId={} userId={} result={}",
                    ls.sessionId(), ls.session().roomId(), ls.session().userId(), result);
            terminate(ls, CloseStatus.NORMAL, budget.completeDeadline());
        } else if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            // 세션은 살려둔다. 대신 드롭을 세어 "조용한 유실"이 되지 않게 한다.
            // 로그는 솎아낸다 — 드롭이 나는 상황이 곧 CPU 포화라, 건당 동기 로그가 그걸 더 악화시킨다.
            long dropped = droppedOnContention.incrementAndGet();
            if (shouldLogDrop()) {
                log.warn("경합 재시도 소진 — 메시지 드롭(세션 유지) sessionId={} roomId={} 누적드롭={}",
                        ls.sessionId(), ls.session().roomId(), dropped);
            }
        }
    }

    /** 단건 전송용 — 이 emit 하나가 예산을 독차지한다. */
    private void emit(LocalSession ls, OutboundMessage message) {
        emit(ls, message, newBudget());
    }

    /**
     * 한 연산(fan-out 1회 또는 단건 전송)이 나눠 쓰는 예산.
     *
     * <p>emit과 종료를 따로 잡는 이유: 종료 신호는 버퍼 잔여분을 최대한 흘려보내야 강퇴 직전에 보낸
     * SYSTEM(KICKED)이 전달되므로 더 넉넉히 준다. 둘 다 <b>연산 시작 시각 기준</b>이라 세션 수만큼
     * 곱해지지 않는다 — 방 fan-out은 방 전체 중계를 담당하는 Redis 구독 스레드 위에서 돈다.
     */
    private record FanOutBudget(long emitDeadline, long completeDeadline) {
    }

    /** 마지막 드롭 로그로부터 간격이 지났으면 true. 경쟁 시 CAS로 한 스레드만 통과한다. */
    private boolean shouldLogDrop() {
        long now = System.nanoTime();
        long last = lastDropLogNanos.get();
        return now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now);
    }

    private FanOutBudget newBudget() {
        long now = System.nanoTime();
        return new FanOutBudget(now + CONTENTION_SPIN_NANOS, now + COMPLETE_SPIN_NANOS);
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
