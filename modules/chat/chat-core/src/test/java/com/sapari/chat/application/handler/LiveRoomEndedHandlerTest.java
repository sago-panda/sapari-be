package com.sapari.chat.application.handler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.LiveRoomEndedSource;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class LiveRoomEndedHandlerTest {

    @Mock
    private LiveRoomEndedSource liveRoomEndedSource;

    @Mock
    private SystemMessageService systemMessageService;

    @Mock
    private ChatSessionManager sessionManager;

    @Mock
    private ChatSessionRepository sessionRepository;

    @InjectMocks
    private LiveRoomEndedHandler handler;

    private final UUID roomId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    @DisplayName("방 종료 처리 성공: 종료 신호가 오면 SYSTEM(ROOM_ENDED) 렌더 → closeAll → Redis 세션 키 정리 순으로 진행한다")
    void onRoomEnded_Success() {
        // given
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then — 세 단계가 모두 일어나는지, 그리고 알림 → 닫기 → 정리 흐름이 유지되는지 고정한다.
        // (정합성이 순서에 의존하지는 않는다 — DEL·HDEL 모두 멱등이라 뒤늦은 unregister도 무해)
        InOrder order = inOrder(systemMessageService, sessionManager, sessionRepository);
        order.verify(systemMessageService).renderToRoom(roomId, SystemMessageCode.ROOM_ENDED);
        order.verify(sessionManager).closeAll(roomId);
        order.verify(sessionRepository).clearRoom(roomId);
    }
}
