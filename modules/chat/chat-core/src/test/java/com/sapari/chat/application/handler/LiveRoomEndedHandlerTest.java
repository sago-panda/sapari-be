package com.sapari.chat.application.handler;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.LiveRoomEndedSource;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LiveRoomEndedHandlerTest {

    private SystemMessageService systemMessageService;
    private ChatSessionManager sessionManager;
    private LiveRoomEndedHandler handler;

    private final UUID roomId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        systemMessageService = mock(SystemMessageService.class);
        sessionManager = mock(ChatSessionManager.class);
        handler = new LiveRoomEndedHandler(mock(LiveRoomEndedSource.class), systemMessageService, sessionManager);
    }

    @Test
    @DisplayName("방 종료 — SYSTEM(ROOM_ENDED) 렌더 후 로컬 세션 closeAll")
    void room_ended_renders_system_then_closes() {
        when(systemMessageService.renderToRoom(eq(roomId), eq(SystemMessageCode.ROOM_ENDED))).thenReturn(Mono.empty());
        when(sessionManager.closeAll(roomId)).thenReturn(Mono.empty());

        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        verify(systemMessageService).renderToRoom(roomId, SystemMessageCode.ROOM_ENDED);
        verify(sessionManager).closeAll(roomId);
    }
}
