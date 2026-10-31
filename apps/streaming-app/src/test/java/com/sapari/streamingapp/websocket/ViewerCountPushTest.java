package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import reactor.core.publisher.Mono;

/**
 * 시청자 수 갱신 push.
 *
 * <p>지금까지 {@code ROOM_INFO} 는 입장 시 1회뿐이라 그 수가 <b>고정 스냅샷</b>이었다 — 앞 커밋이
 * 좀비를 걷어내 수를 정확하게 만들었지만, 그 정확한 수를 이미 접속한 세션에 전달할 수단이 없었다.
 *
 * <p>프레임을 새로 만들지 않고 {@code ROOM_INFO} 를 다시 보낸다. 프론트 가이드의 클라 핸들러가
 * <b>순수 상태 setter</b>({@code setActiveCount} + {@code setOwnerMode})라 반복 수신이 멱등이고,
 * 반대로 모르는 {@code type} 을 어떻게 다루는지는 계약에 없어서 새 타입이 더 위험하다.
 *
 * <p>세 가지를 본다 — <b>변했을 때만</b> 보내는가(조용한 방이 비용 0), <b>수를 모를 때</b> 안 보내는가
 * (조회 실패에 {@code null} 을 보내면 화면의 숫자가 사라진다), 그리고 세션마다 자기
 * {@code isRoomOwner} 를 받는가.
 */
@DisplayName("시청자 수 갱신 — 변했을 때만, 모를 때는 보내지 않는다")
class ViewerCountPushTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private ChatSessionRepository sessionRepository;
    private ChatSessionRegistry registry;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID buyerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        lenient().when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.remove(any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.heartbeat(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP);
    }

    private void register(String sessionId, UUID userId, boolean roomOwner) {
        registry.register(sessionId, new ChatSession(roomId, userId,
                roomOwner ? ChatRole.SELLER : ChatRole.BUYER, "닉", "e@example.com", roomOwner))
                .block(VERIFY_CEILING);
    }

    /** 그 세션이 받은 프레임 전부. */
    private List<OutboundMessage> received(String sessionId) {
        List<OutboundMessage> frames = new ArrayList<>();
        registry.outbound(sessionId).subscribe(frames::add);
        return frames;
    }

    @Test
    @DisplayName("⭐ 수가 변하면 그 방 세션에 ROOM_INFO 가 다시 간다")
    void aChangedCountIsPushed() {
        // given: 입장 시 1 → 첫 회차가 기준선을 잡고(안 보냄) → 두 번째 회차에 7
        // register 는 count 를 읽지 않는다 — 입장 ROOM_INFO 는 핸들러(roomInfoFor)가 보낸다.
        // 그래서 첫 회차가 기준선 1 을 잡고, 두 번째 회차가 7 을 본다.
        given(sessionRepository.count(roomId)).willReturn(Mono.just(1L), Mono.just(7L));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when: 첫 회차는 기준선(이 Pod 가 그 방을 처음 보는 회차)이라 보내지 않는다
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then
        assertThat(frames).extracting(OutboundMessage::type).containsOnly("ROOM_INFO");
        assertThat(frames).extracting(OutboundMessage::activeCount).containsExactly(7L);
    }

    @Test
    @DisplayName("⭐ 수가 그대로면 아무것도 보내지 않는다 — 조용한 방이 비용 0 이어야 한다")
    void anUnchangedCountIsNotPushed() {
        // given
        given(sessionRepository.count(roomId)).willReturn(Mono.just(3L));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when: 여러 회차를 돌려도
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then
        assertThat(frames).as("변하지 않은 수를 매 회차 뿌리면 큰 방에서 그 비용이 전부다").isEmpty();
    }

    @Test
    @DisplayName("⭐ 수를 모를 때는 보내지 않는다 — null 을 보내면 화면의 숫자가 사라진다")
    void anUnknownCountIsNotPushed() {
        // given: 첫 회차가 기준선 5 를 잡고, 그 뒤 조회가 실패한다
        given(sessionRepository.count(roomId))
                .willReturn(Mono.just(5L), Mono.error(new IllegalStateException("Redis down")));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when: 두 번째 회차가 실패 회차다 — 한 번만 돌리면 그 분기에 닿지도 않는다
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then: ROOM_INFO 의 activeCount 는 "모른다"를 뜻하는 null 을 가질 수 있는데,
        // 그걸 갱신으로 보내면 보이던 숫자가 사라진다 — 입장 시의 "모름"과 다른 상황이다.
        assertThat(frames).isEmpty();
    }

    @Test
    @DisplayName("⭐ 세션마다 자기 isRoomOwner 를 받는다 — 한 프레임을 공유하면 방 주인 UI 가 전원에게 켜진다")
    void eachSessionGetsItsOwnOwnerFlag() {
        // given
        given(sessionRepository.count(roomId)).willReturn(Mono.just(1L), Mono.just(9L));
        register("owner", sellerId, true);
        register("viewer", buyerId, false);
        List<OutboundMessage> ownerFrames = received("owner");
        List<OutboundMessage> viewerFrames = received("viewer");

        // when
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then
        assertThat(ownerFrames).singleElement()
                .satisfies(frame -> assertThat(frame.isRoomOwner()).isTrue());
        assertThat(viewerFrames).singleElement()
                .satisfies(frame -> assertThat(frame.isRoomOwner()).isFalse());
    }

    @Test
    @DisplayName("로컬 세션이 없는 방은 조회조차 하지 않는다 — Pod 마다 전 방을 읽으면 안 된다")
    void roomsWithoutLocalSessionsAreNotRead() {
        // when: 아무도 등록하지 않았다
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then
        org.mockito.BDDMockito.then(sessionRepository).should(org.mockito.Mockito.never()).count(any());
    }

    @Test
    @DisplayName("방이 비면 마지막으로 보낸 수도 함께 걷힌다 — 남기면 그쪽이 누수원이 된다")
    void theLastPushedCountIsReclaimedWithTheRoom() {
        // given
        given(sessionRepository.count(roomId)).willReturn(Mono.just(1L));
        register("s1", buyerId, false);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // when
        registry.unregister(roomId, "s1").block(VERIFY_CEILING);

        // then
        assertThat(registry.trackedViewerCounts()).isZero();
    }

    @Test
    @DisplayName("⭐ 조회 중에 방이 비면 마지막 값을 되살리지 않는다 — 되살리면 아무도 회수하지 않아 영구 잔존이다")
    void aLateWriteDoesNotResurrectTheReclaimedEntry() {
        // given: 조회가 해소되는 순간에 방이 비게 만든다 — unregister 의 회수가 먼저 일어나고
        // 이 회차의 쓰기가 그 뒤에 도착하는 모양이다. 방송마다 항목 하나가 쌓이는 누수였다.
        register("s1", buyerId, false);
        given(sessionRepository.count(roomId)).willReturn(Mono.fromCallable(() -> {
            registry.unregister(roomId, "s1").block(VERIFY_CEILING);
            return 1L;
        }));

        // when
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then
        assertThat(registry.trackedViewerCounts()).isZero();
    }
}
