package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.domain.model.ChatConstants;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SystemMessageServiceTest {

    @Mock
    private ChatSessionManager sessionManager;

    @InjectMocks
    private SystemMessageService service;

    @Test
    @DisplayName("세션 시스템 메시지 렌더 성공: 세션 하나가 주어지면 발신자를 고정 시스템 UUID로 채우고 본문 없이 code만 실어 보낸다")
    void renderToSession_Success() {
        // given
        given(sessionManager.sendToSession(any(), any())).willReturn(Mono.empty());
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);

        // when
        StepVerifier.create(service.renderToSession("sess-1", SystemMessageCode.KICKED))
                .verifyComplete();

        // then
        then(sessionManager).should(times(1)).sendToSession(eq("sess-1"), captor.capture());
        OutboundMessage msg = captor.getValue();
        assertThat(msg.type()).isEqualTo("SYSTEM");
        assertThat(msg.code()).isEqualTo("KICKED");
        assertThat(msg.senderId()).isEqualTo(ChatConstants.SYSTEM_SENDER_ID);
        assertThat(msg.senderNickname()).isEqualTo("SYSTEM");
        assertThat(msg.displayMessage()).isNull();   // SYSTEM은 code로 클라가 렌더
        assertThat(msg.id()).isNull();
    }

    @Test
    @DisplayName("방 시스템 메시지 렌더 성공: 방이 주어지면 그 방의 로컬 세션 전체에 SYSTEM(code)을 보낸다")
    void renderToRoom_Success() {
        // given
        UUID roomId = UUID.randomUUID();
        given(sessionManager.sendToRoomLocal(any(), any())).willReturn(Mono.empty());
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);

        // when
        StepVerifier.create(service.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED))
                .verifyComplete();

        // then
        then(sessionManager).should(times(1)).sendToRoomLocal(eq(roomId), captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("ROOM_ENDED");
        assertThat(captor.getValue().type()).isEqualTo("SYSTEM");
    }
}
