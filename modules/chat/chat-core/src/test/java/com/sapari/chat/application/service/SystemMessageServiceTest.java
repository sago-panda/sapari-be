package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.domain.model.ChatConstants;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SystemMessageServiceTest {

    private ChatSessionManager sessionManager;
    private SystemMessageService service;

    @BeforeEach
    void setUp() {
        sessionManager = mock(ChatSessionManager.class);
        service = new SystemMessageService(sessionManager);
    }

    @Test
    @DisplayName("renderToSession — 세션 1개에 SYSTEM(code) 송신, 발신자는 고정 시스템 UUID·displayMessage 없음")
    void renders_system_to_single_session() {
        when(sessionManager.sendToSession(any(), any())).thenReturn(Mono.empty());
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);

        StepVerifier.create(service.renderToSession("sess-1", SystemMessageCode.KICKED))
                .verifyComplete();

        verify(sessionManager).sendToSession(eq("sess-1"), captor.capture());
        OutboundMessage msg = captor.getValue();
        assertThat(msg.type()).isEqualTo("SYSTEM");
        assertThat(msg.code()).isEqualTo("KICKED");
        assertThat(msg.senderId()).isEqualTo(ChatConstants.SYSTEM_SENDER_ID);
        assertThat(msg.senderNickname()).isEqualTo("SYSTEM");
        assertThat(msg.displayMessage()).isNull();   // SYSTEM은 code로 클라가 렌더
        assertThat(msg.id()).isNull();
    }

    @Test
    @DisplayName("renderToRoom — 방 로컬 세션 전체에 SYSTEM(code) 송신")
    void renders_system_to_room_local() {
        UUID roomId = UUID.randomUUID();
        when(sessionManager.sendToRoomLocal(any(), any())).thenReturn(Mono.empty());
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);

        StepVerifier.create(service.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED))
                .verifyComplete();

        verify(sessionManager).sendToRoomLocal(eq(roomId), captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("ROOM_ENDED");
        assertThat(captor.getValue().type()).isEqualTo("SYSTEM");
    }
}
