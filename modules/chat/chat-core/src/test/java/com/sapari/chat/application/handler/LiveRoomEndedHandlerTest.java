package com.sapari.chat.application.handler;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.LiveRoomEndedSource;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LiveRoomEndedHandlerTest {

    private SystemMessageService systemMessageService;
    private ChatSessionManager sessionManager;
    private ChatSessionRepository sessionRepository;
    private LiveRoomEndedHandler handler;

    private final UUID roomId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        systemMessageService = mock(SystemMessageService.class);
        sessionManager = mock(ChatSessionManager.class);
        sessionRepository = mock(ChatSessionRepository.class);
        handler = new LiveRoomEndedHandler(mock(LiveRoomEndedSource.class), systemMessageService, sessionManager,
                sessionRepository);
    }

    @Test
    @DisplayName("방 종료 — SYSTEM(ROOM_ENDED) 렌더 → closeAll → Redis 세션 키 정리 순서")
    void room_ended_renders_closes_then_clears() {
        when(systemMessageService.renderToRoom(eq(roomId), eq(SystemMessageCode.ROOM_ENDED))).thenReturn(Mono.empty());
        when(sessionManager.closeAll(roomId)).thenReturn(Mono.empty());
        when(sessionRepository.clearRoom(roomId)).thenReturn(Mono.empty());

        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // 세 단계가 모두 일어나는지, 그리고 알림 → 닫기 → 정리 흐름이 유지되는지 고정한다.
        // (정합성이 순서에 의존하지는 않는다 — DEL·HDEL 모두 멱등이라 뒤늦은 unregister도 무해)
        InOrder order = inOrder(systemMessageService, sessionManager, sessionRepository);
        order.verify(systemMessageService).renderToRoom(roomId, SystemMessageCode.ROOM_ENDED);
        order.verify(sessionManager).closeAll(roomId);
        order.verify(sessionRepository).clearRoom(roomId);
    }
}
