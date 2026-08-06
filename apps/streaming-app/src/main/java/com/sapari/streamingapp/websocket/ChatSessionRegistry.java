package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

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

    /** 동시 emit 경합 재시도 상한(벽시계 아님 — 스핀 시간 측정이라 TimeProvider 대상이 아니다). */
    private static final long CONTENTION_SPIN_NANOS = Duration.ofMillis(100).toNanos();

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

    private record LocalSession(ChatSession session, Sinks.Many<OutboundMessage> sink) {
    }

    @Override
    public Mono<Void> register(String sessionId, ChatSession session) {
        // 세션마다 unicast Sink 1개(연결당 아웃바운드 1개). onBackpressureBuffer: 구독 전 emit·일시 적체 보관.
        // 버퍼는 유계 — 무제한이면 소비하지 않는 클라 하나가 Pod 힙을 잠식한다(초과 처리는 emit 참고).
        local.put(sessionId, new LocalSession(session,
                Sinks.many().unicast().onBackpressureBuffer(Queues.<OutboundMessage>get(OUTBOUND_BUFFER_SIZE).get())));
        // compute — 집합 생성과 추가를 한 원자 구간에 묶는다. computeIfAbsent 후 add로 나누면 그 사이
        // 마지막 퇴장이 빈 집합을 걷어내 방금 넣은 세션이 인덱스에서 사라질 수 있다.
        roomSessions.compute(session.roomId(), (room, sessionIds) -> {
            Set<String> ids = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            ids.add(sessionId);
            return ids;
        });
        return sessionRepository.add(session.roomId(), sessionId, session.userId());
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
        return sessionRepository.remove(roomId, sessionId);
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
        return Mono.fromRunnable(() -> forEachInRoom(roomId, ls -> emit(ls, message)));
    }

    @Override
    public Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver) {
        // 세션별 차등 fan-out — resolver가 세션마다 메시지 생성(방주인 PII 게이팅·kick 분기). null이면 그 세션 skip.
        return Mono.fromRunnable(() -> forEachInRoom(roomId, ls -> {
            OutboundMessage message = resolver.apply(ls.session());
            if (message != null) {
                emit(ls, message);
            }
        }));
    }

    @Override
    public Mono<Void> closeUser(UUID roomId, UUID userId) {
        // Sink complete → 아웃바운드 종료. C4 핸들러가 이를 실제 WS close로 잇고, disconnect 콜백이 unregister(Redis 정리).
        return Mono.fromRunnable(() -> forEachInRoom(roomId, ls -> {
            if (ls.session().userId().equals(userId)) {
                complete(ls);
            }
        }));
    }

    @Override
    public Mono<Void> closeAll(UUID roomId) {
        return Mono.fromRunnable(() -> forEachInRoom(roomId, this::complete));
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
     * <p>버퍼 초과({@code FAIL_OVERFLOW})면 유실 대신 <b>세션을 끊는다</b> — 조용히 빠뜨리면 클라 화면이
     * 어긋난 채 유지되지만, 끊으면 프론트가 재접속·이력 재조회로 복구할 수 있다. 이미 끝난 세션
     * ({@code FAIL_TERMINATED}/{@code FAIL_CANCELLED})은 정상 흐름이라 무시한다.
     */
    private void emit(LocalSession ls, OutboundMessage message) {
        if (ls == null) {
            return;
        }
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitNext(message));
        if (result == Sinks.EmitResult.FAIL_OVERFLOW || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("아웃바운드 버퍼 초과 — 느린 클라 세션 종료 roomId={} result={}", ls.session().roomId(), result);
            complete(ls);
        }
    }

    /** 종료 신호도 emit과 같은 경합을 겪는다 — 놓치면 세션이 안 닫히므로 같은 재시도를 태운다. */
    private void complete(LocalSession ls) {
        emitSerially(() -> ls.sink().tryEmitComplete());
    }

    /**
     * Sink는 동시 emit을 직렬화하지 않는다 — 경합하면 {@code FAIL_NON_SERIALIZED}를 돌려주고 값을 버린다.
     * 한 세션에 ack(WS 이벤트루프)와 방 브로드캐스트(Redis 구독 스레드)가 동시에 들어오므로, Reactor 권장대로
     * 경합 구간만 짧게 busy-loop 재시도한다. 경합은 마이크로초 단위라 상한에 닿는 일은 사실상 없고,
     * 상한은 이벤트루프가 무한정 묶이지 않게 하는 안전장치다.
     */
    private Sinks.EmitResult emitSerially(Supplier<Sinks.EmitResult> emitter) {
        long deadline = System.nanoTime() + CONTENTION_SPIN_NANOS;
        while (true) {
            Sinks.EmitResult result = emitter.get();
            if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED || System.nanoTime() >= deadline) {
                return result;
            }
            Thread.onSpinWait();
        }
    }
}
