package com.sapari.chat.application.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

import reactor.core.publisher.Flux;
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
        given(kickRepository.expireAfterRoomEnded(roomId)).willReturn(Mono.empty());

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
        order.verify(kickRepository).expireAfterRoomEnded(roomId);
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
        given(kickRepository.expireAfterRoomEnded(roomId)).willReturn(Mono.empty());

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
        given(kickRepository.expireAfterRoomEnded(roomId)).willReturn(Mono.empty());

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
        given(kickRepository.expireAfterRoomEnded(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then
        then(sessionRepository).should(times(1)).clearRoom(roomId);
        then(kickRepository).should(times(1)).expireAfterRoomEnded(roomId);
    }

    @Test
    @DisplayName("세션 키 정리가 실패해도 강퇴 명단은 지운다 — 이쪽은 TTL 백스톱이 없어 여기가 유일한 회수 지점이다")
    void onRoomEnded_ClearsKickedEvenWhenSessionCleanupFails() {
        // given
        given(roomEndedRepository.markEnded(roomId)).willReturn(Mono.empty());
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.error(new RuntimeException("redis down")));
        given(kickRepository.expireAfterRoomEnded(roomId)).willReturn(Mono.empty());

        // when
        StepVerifier.create(handler.onRoomEnded(roomId)).verifyComplete();

        // then
        then(kickRepository).should(times(1)).expireAfterRoomEnded(roomId);
    }

    // ── 구독 자가복구 ──
    // 이 구독이 죽으면 이 Pod는 재시작 전까지 모든 방 종료를 놓친다. 그러면 세션이 안 닫히고,
    // 끝난 방에 계속 글이 쌓인다. 아래 둘은 그 구독이 무슨 일이 있어도 살아남는지를 본다.

    @Test
    @DisplayName("한 방 처리가 실패해도 다음 방 종료는 정상 처리된다 — 봉투 하나가 구독을 죽이면 안 된다")
    void start_survivesFailureOfOneRoom() {
        // given: 첫 방은 알림에서 터지고, 둘째 방은 정상
        UUID broken = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        given(liveRoomEndedSource.ended()).willReturn(Flux.just(broken, roomId));
        given(roomEndedRepository.markEnded(any())).willReturn(Mono.empty());
        given(systemMessageService.renderToRoom(broken, SystemMessageCode.ROOM_ENDED))
                .willReturn(Mono.error(new RuntimeException("render 실패")));
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(any())).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(any())).willReturn(Mono.empty());
        given(kickRepository.expireAfterRoomEnded(any())).willReturn(Mono.empty());

        // when
        handler.start();

        // then: 둘 다 끝까지 갔다
        then(sessionManager).should(times(1)).closeAll(broken);
        then(sessionManager).should(times(1)).closeAll(roomId);
    }

    @Test
    @DisplayName("조립 시점에 터져도 구독이 살아남는다 — defer로 감싸지 않으면 여기서 스트림이 죽는다")
    void start_survivesAssemblyTimeThrow() {
        // given: 포트가 Mono를 돌려주기 전에 동기 throw하는 상황(어댑터 버그·설정 오류).
        // defer 없이 조립하면 이 예외가 onErrorResume을 지나쳐 구독 자체를 끝낸다.
        UUID broken = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        given(liveRoomEndedSource.ended()).willReturn(Flux.just(broken, roomId));
        given(roomEndedRepository.markEnded(broken)).willThrow(new IllegalStateException("조립 중 폭발"));
        given(roomEndedRepository.markEnded(roomId)).willReturn(Mono.empty());
        given(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sessionManager.closeAll(roomId)).willReturn(Mono.empty());
        given(sessionRepository.clearRoom(roomId)).willReturn(Mono.empty());
        given(kickRepository.expireAfterRoomEnded(roomId)).willReturn(Mono.empty());

        // when
        handler.start();

        // then: 뒤따르는 방은 그대로 처리된다
        then(sessionManager).should(times(1)).closeAll(roomId);
    }

    @Test
    @DisplayName("종료 시 구독을 실제로 해제한다 — Pod가 내려가는데 스트림이 남으면 안 된다")
    void stop_disposesSubscription() {
        // given: 취소가 업스트림까지 전파되는지 관측한다. 이걸 안 보면 stop() 본문을 비워도 통과한다.
        AtomicBoolean cancelled = new AtomicBoolean();
        given(liveRoomEndedSource.ended())
                .willReturn(Flux.<UUID>never().doOnCancel(() -> cancelled.set(true)));
        handler.start();
        assertThat(cancelled).isFalse();

        // when
        handler.stop();

        // then
        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("stop을 두 번 불러도 안전하다 — 컨텍스트 종료 경로가 중복 호출될 수 있다")
    void stop_isIdempotent() {
        // given
        given(liveRoomEndedSource.ended()).willReturn(Flux.never());
        handler.start();

        // when & then
        handler.stop();
        handler.stop();
    }

    @Test
    @DisplayName("start 없이 stop을 불러도 터지지 않는다 — 부팅 실패 후 컨텍스트 종료 경로")
    void stop_withoutStart_isSafe() {
        // when & then
        handler.stop();
    }
}
