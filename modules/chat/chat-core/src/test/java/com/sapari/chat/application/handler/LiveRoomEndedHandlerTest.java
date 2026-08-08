package com.sapari.chat.application.handler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
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
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;
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

    @Mock
    private ChatRoomEndedRepository roomEndedRepository;

    @Mock
    private ChatKickRepository kickRepository;

    @InjectMocks
    private LiveRoomEndedHandler handler;

    private final UUID roomId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    @DisplayName("방 종료 처리 성공: 마커 → SYSTEM(ROOM_ENDED) → closeAll → 세션 키 정리 → 강퇴 명단 정리 순으로 진행한다")
    void onRoomEnded_Success() {
        // given
        given(roomEndedRepository.markEnded(roomId)).willReturn(Mono.empty());
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.empty());
        given(kickRepository.clearRoom(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then — 세 단계가 모두 일어나는지, 그리고 알림 → 닫기 → 정리 흐름이 유지되는지 고정한다.
        // (정합성이 순서에 의존하지는 않는다 — DEL·HDEL 모두 멱등이라 뒤늦은 unregister도 무해)
        InOrder order = inOrder(roomEndedRepository, systemMessageService, sessionManager, sessionRepository,
                kickRepository);
        // 마커가 가장 먼저다 — 세션을 닫은 뒤에 쓰면 그 사이 재접속이 게이트를 통과한다
        order.verify(roomEndedRepository).markEnded(roomId);
        order.verify(systemMessageService).renderToRoom(roomId, SystemMessageCode.ROOM_ENDED);
        order.verify(sessionManager).closeAll(roomId);
        order.verify(sessionRepository).clearRoom(roomId);
        order.verify(kickRepository).clearRoom(roomId);
    }

    @Test
    @DisplayName("마커 기록 실패해도 세션 종료는 끝까지 진행한다 — 여기서 멈추면 세션이 아예 안 닫힌다")
    void onRoomEnded_ContinuesWhenMarkerFails() {
        // given
        given(roomEndedRepository.markEnded(roomId))
                .willReturn(Mono.error(new RuntimeException("redis down")));
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.empty());
        given(kickRepository.clearRoom(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then
        then(sessionManager).should(times(1)).closeAll(roomId);
        then(sessionRepository).should(times(1)).clearRoom(roomId);
    }

    @Test
    @DisplayName("앞 단계가 실패해도 세션 종료까지 간다 — 알림 하나 때문에 세션이 남으면 안 된다")
    void onRoomEnded_ContinuesWhenEarlierStepFails() {
        // given: 마커와 알림이 둘 다 실패하는 상황
        given(roomEndedRepository.markEnded(roomId)).willReturn(Mono.error(new RuntimeException("redis down")));
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED))
                .willReturn(Mono.error(new RuntimeException("render failed")));
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.empty());
        given(kickRepository.clearRoom(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then: 이 체인의 본 일(세션 종료)과 정리는 그대로 수행된다
        then(sessionManager).should(times(1)).closeAll(roomId);
        then(sessionRepository).should(times(1)).clearRoom(roomId);
    }

    @Test
    @DisplayName("세션 종료가 실패해도 키 정리는 시도한다 — 각 단계는 자기 몫만 잃는다")
    void onRoomEnded_ClearsRoomEvenWhenCloseFails() {
        // given
        given(roomEndedRepository.markEnded(roomId)).willReturn(Mono.empty());
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.error(new RuntimeException("close failed")));
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.empty());
        given(kickRepository.clearRoom(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then
        then(sessionRepository).should(times(1)).clearRoom(roomId);
        then(kickRepository).should(times(1)).clearRoom(roomId);
    }

    @Test
    @DisplayName("세션 키 정리가 실패해도 강퇴 명단은 지운다 — 이쪽은 TTL 백스톱이 없어 여기가 유일한 회수 지점이다")
    void onRoomEnded_ClearsKickedEvenWhenSessionCleanupFails() {
        // given
        given(roomEndedRepository.markEnded(roomId)).willReturn(Mono.empty());
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.error(new RuntimeException("redis down")));
        given(kickRepository.clearRoom(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then
        then(kickRepository).should(times(1)).clearRoom(roomId);
    }
}
