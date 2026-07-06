package com.sapari.streamingapp.websocket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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
@Component
@RequiredArgsConstructor
public class ChatSessionRegistry implements ChatSessionManager {

    private final ChatSessionRepository sessionRepository;   // Redis HASH 어댑터(T6) — 크로스 Pod activeCount

    /** sessionId → (도메인 세션 + 아웃바운드 Sink). 로컬 메모리(이 Pod 한정). */
    private final Map<String, LocalSession> local = new ConcurrentHashMap<>();

    private record LocalSession(ChatSession session, Sinks.Many<OutboundMessage> sink) {
    }

    @Override
    public Mono<Void> register(String sessionId, ChatSession session) {
        // 세션마다 unicast Sink 1개(연결당 아웃바운드 1개). onBackpressureBuffer: 구독 전 emit·일시 적체 보관.
        local.put(sessionId, new LocalSession(session, Sinks.many().unicast().onBackpressureBuffer()));
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
        // 이 Pod의 해당 방 세션 전체에 emit. (로컬 맵 스캔 — 시스템 메시지는 드물어 허용, 규모 확대 시 room 인덱스)
        return Mono.fromRunnable(() -> local.values().stream()
                .filter(ls -> ls.session().roomId().equals(roomId))
                .forEach(ls -> emit(ls, message)));
    }

    @Override
    public Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver) {
        // 세션별 차등 fan-out — resolver가 세션마다 메시지 생성(방주인 PII 게이팅·kick 분기). null이면 그 세션 skip.
        return Mono.fromRunnable(() -> local.values().stream()
                .filter(ls -> ls.session().roomId().equals(roomId))
                .forEach(ls -> {
                    OutboundMessage message = resolver.apply(ls.session());
                    if (message != null) {
                        emit(ls, message);
                    }
                }));
    }

    @Override
    public Mono<Void> closeUser(UUID roomId, UUID userId) {
        // Sink complete → 아웃바운드 종료. C4 핸들러가 이를 실제 WS close로 잇고, disconnect 콜백이 unregister(Redis 정리).
        return Mono.fromRunnable(() -> local.values().stream()
                .filter(ls -> ls.session().roomId().equals(roomId) && ls.session().userId().equals(userId))
                .forEach(ls -> ls.sink().tryEmitComplete()));
    }

    @Override
    public Mono<Void> closeAll(UUID roomId) {
        return Mono.fromRunnable(() -> local.values().stream()
                .filter(ls -> ls.session().roomId().equals(roomId))
                .forEach(ls -> ls.sink().tryEmitComplete()));
    }

    // best-effort emit — Sink는 무계 버퍼라 느린/끊긴 클라엔 적체될 수 있다(슬로우 컨슈머 bounded buffer는 후속 한계 항목). null 세션은 무시.
    private void emit(LocalSession ls, OutboundMessage message) {
        if (ls != null) {
            ls.sink().tryEmitNext(message);
        }
    }
}
