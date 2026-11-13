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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>네 가지를 본다 — <b>변했을 때만</b> 보내는가(조용한 방이 비용 0), 마지막으로 보낸 수가 없을 때
 * (첫 회차 · 새 입장 뒤) 보내는가, <b>수를 모를 때</b> 안 보내는가
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
        // given: 첫 회차 1 → 두 번째 회차 7
        // register 는 count 를 읽지 않는다 — 입장 ROOM_INFO 는 핸들러(roomInfoFor)가 보낸다.
        given(sessionRepository.count(roomId)).willReturn(Mono.just(1L), Mono.just(7L));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then: 첫 회차는 보낸 수가 없어서 보내고, 두 번째는 변해서 보낸다
        assertThat(frames).extracting(OutboundMessage::type).containsOnly("ROOM_INFO");
        assertThat(frames).extracting(OutboundMessage::activeCount).containsExactly(1L, 7L);
    }

    @Test
    @DisplayName("⭐ 수가 그대로면 첫 회차 뒤로는 아무것도 보내지 않는다 — 조용한 방이 비용 0 이어야 한다")
    void anUnchangedCountIsNotPushed() {
        // given
        given(sessionRepository.count(roomId)).willReturn(Mono.just(3L));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when: 여러 회차를 돌려도
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then: 첫 회차의 한 번뿐이다
        assertThat(frames).as("변하지 않은 수를 매 회차 뿌리면 큰 방에서 그 비용이 전부다")
                .extracting(OutboundMessage::activeCount).containsExactly(3L);
    }

    @Test
    @DisplayName("⭐ 수를 모를 때는 보내지 않는다 — null 을 보내면 화면의 숫자가 사라진다")
    void anUnknownCountIsNotPushed() {
        // given: 첫 회차가 5 를 보내고, 그 뒤 조회가 실패한다
        given(sessionRepository.count(roomId))
                .willReturn(Mono.just(5L), Mono.error(new IllegalStateException("Redis down")));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when: 두 번째 회차가 실패 회차다 — 한 번만 돌리면 그 분기에 닿지도 않는다
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then: ROOM_INFO 의 activeCount 는 "모른다"를 뜻하는 null 을 가질 수 있는데,
        // 그걸 갱신으로 보내면 보이던 숫자가 사라진다 — 입장 시의 "모름"과 다른 상황이다.
        assertThat(frames).extracting(OutboundMessage::activeCount).containsExactly(5L);
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

        // then: 두 회차 모두 보낸다(첫 회차 · 변함) — 어느 프레임도 남의 플래그를 싣지 않는다
        assertThat(ownerFrames).hasSize(2).allSatisfy(frame -> assertThat(frame.isRoomOwner()).isTrue());
        assertThat(viewerFrames).hasSize(2).allSatisfy(frame -> assertThat(frame.isRoomOwner()).isFalse());
    }

    @Test
    @DisplayName("⭐ 첫 회차가 입장 때와 다른 수를 보면 보낸다 — 건너뛰면 인원이 다시 변할 때까지 틀린 수가 남는다")
    void theFirstCycleCorrectsTheEntrySnapshot() {
        // given: 입장 ROOM_INFO 는 캐시를 거친 값이라 지금 수와 다를 수 있다. 그 뒤 인원이 안 변한다.
        given(sessionRepository.count(roomId)).willReturn(Mono.just(4L));
        register("s1", buyerId, false);
        List<OutboundMessage> frames = received("s1");

        // when
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then
        assertThat(frames).extracting(OutboundMessage::activeCount).containsExactly(4L);
    }

    @Test
    @DisplayName("⭐ 새 입장 뒤 회차는 수가 같아도 보낸다 — 들어온 세션이 받은 입장 수를 맞춘다")
    void anEntryMakesTheNextCycleSendEvenIfTheCountIsUnchanged() {
        // given: 회차가 한 번 돈 뒤 새 세션이 들어온다. 방 수는 회차 사이에 그대로다
        // (한 명 나가고 한 명 들어온 경우와 같은 모양).
        given(sessionRepository.count(roomId)).willReturn(Mono.just(2L));
        register("s1", buyerId, false);
        registry.pushViewerCounts().block(VERIFY_CEILING);
        register("s2", UUID.randomUUID(), false);
        registry.forgetLastPushedCount(roomId);   // 핸들러가 입장 ROOM_INFO 를 보낸 뒤 부르는 자리
        List<OutboundMessage> newcomer = received("s2");

        // when
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then: 입장 뒤 첫 회차 한 번, 그다음은 변하지 않아 없다
        assertThat(newcomer).extracting(OutboundMessage::activeCount).containsExactly(2L);
    }

    /**
     * ⭐ 등록과 입장 ROOM_INFO 사이에 회차가 끼어도 그다음 회차가 맞춘다.
     *
     * <p>지우기가 등록 안에 있으면, 낀 회차가 맞는 수를 보내고 기록한 뒤 캐시의 수가 나중에 도착해 화면에
     * 남고 다음 회차는 기록과 같아 건너뛴다. 보낸 뒤에 지우면 낀 회차의 기록도 지워져 한 번 더 보낸다.
     */
    @Test
    @DisplayName("⭐ 입장 ROOM_INFO 보다 먼저 낀 회차가 있어도 그다음 회차가 다시 보낸다 — 늦게 온 캐시 수가 남지 않는다")
    void aCycleBetweenRegisterAndTheEntryRoomInfoDoesNotPinTheCachedCount() {
        // given: 등록 직후, 입장 ROOM_INFO 가 나가기 전에 회차가 돈다
        given(sessionRepository.count(roomId)).willReturn(Mono.just(2L));
        register("s1", buyerId, false);
        List<OutboundMessage> newcomer = received("s1");
        registry.pushViewerCounts().block(VERIFY_CEILING);
        // 여기서 캐시를 거친 입장 ROOM_INFO(다른 수)가 도착했다고 본다 — 핸들러가 그 뒤에 지운다
        registry.forgetLastPushedCount(roomId);

        // when
        registry.pushViewerCounts().block(VERIFY_CEILING);
        registry.pushViewerCounts().block(VERIFY_CEILING);

        // then: 낀 회차 한 번 + 입장 ROOM_INFO 뒤 회차 한 번, 그다음은 없다
        assertThat(newcomer).extracting(OutboundMessage::activeCount).containsExactly(2L, 2L);
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

    @Test
    @DisplayName("⭐ 시청자 수 갱신이 하트비트 타이머에 얹혀 있다 — 떼면 수가 다시 고정 스냅샷이 된다")
    void theHeartbeatTimerAlsoPushesViewerCounts() {
        // given: 위 테스트들은 pushViewerCounts() 를 직접 불러서 <b>계산</b>만 보고 있었다. 그래서
        // startHeartbeat() 에서 그 호출 한 줄을 떼도 이 앱의 테스트가 전부 초록이었다(실측). 갱신이
        // 아무 주기에도 얹히지 않으면 ROOM_INFO 는 입장 시 1회로 돌아가고, 화면의 시청자 수는 다시
        // 고정 스냅샷이 된다 — 계산은 정확한데 아무에게도 도달하지 않는 모양이다.
        //
        // 주기를 짧게 줘서 회차를 기다린다. 첫 회차는 보낸 수가 없어 7 을 보내고, 그 뒤는 다시 7 이라
        // 변화가 없어 프레임이 하나로 끝난다.
        given(sessionRepository.heartbeatInterval()).willReturn(Duration.ofMillis(30));
        given(sessionRepository.count(roomId)).willReturn(Mono.just(7L));
        register("s1", buyerId, false);
        // 타이머 스레드가 쓰고 이 스레드가 읽으므로 ArrayList 로는 가시성 보장이 없다
        List<OutboundMessage> frames = new CopyOnWriteArrayList<>();
        registry.outbound("s1").subscribe(frames::add);

        // when
        registry.startHeartbeat();
        try {
            long deadline = System.nanoTime() + VERIFY_CEILING.toNanos();
            while (frames.isEmpty() && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }
        } finally {
            // 타이머를 끄지 않으면 이 테스트가 끝난 뒤에도 돌아 다음 테스트의 목을 건드린다
            registry.stopHeartbeat();
        }

        // then
        assertThat(frames).extracting(OutboundMessage::type).containsOnly("ROOM_INFO");
        assertThat(frames).extracting(OutboundMessage::activeCount).containsExactly(7L);
    }
    @Test
    @DisplayName("⭐ 한 회차가 실패해도 타이머가 산다 — 죽으면 이 파드는 재시작까지 재등록을 전부 잃는다")
    void aFailedRoundDoesNotKillTheTimer() {
        // given: 첫 회차만 실패하는 레지스트리. 오늘 운영 코드에서는 회차 단위 onError 에 도달하지
        // 않는다(잎 셋이 모두 흡수한다) — 그래서 이 테스트가 그 자리를 직접 만든다. 막으려는 것은
        // "잎 하나가 흡수를 빼먹는 변경" 이고, 그때 깨지는 것은 한 회차가 아니라 이 파드의 관측 전부다.
        given(sessionRepository.heartbeatInterval()).willReturn(Duration.ofMillis(30));
        given(sessionRepository.count(roomId)).willReturn(Mono.just(7L));
        AtomicInteger rounds = new AtomicInteger();
        ChatSessionRegistry failingFirstRound = new ChatSessionRegistry(
                sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP) {
            @Override
            Mono<Void> pushViewerCounts() {
                if (rounds.incrementAndGet() == 1) {
                    return Mono.error(new IllegalStateException("회차 실패"));
                }
                return super.pushViewerCounts();
            }
        };
        failingFirstRound.register("s1", new ChatSession(roomId, buyerId, ChatRole.BUYER,
                "닉", "e@example.com", false)).block(VERIFY_CEILING);
        List<OutboundMessage> frames = new CopyOnWriteArrayList<>();
        failingFirstRound.outbound("s1").subscribe(frames::add);

        // when: 1회차 실패 → 2회차가 7 을 보낸다. 흡수가 없으면 1회차에서 바깥 subscribe() 가
        // 끝나므로 2회차가 아예 오지 않고 프레임도 영원히 없다.
        failingFirstRound.startHeartbeat();
        try {
            long deadline = System.nanoTime() + VERIFY_CEILING.toNanos();
            while (frames.isEmpty() && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }
        } finally {
            failingFirstRound.stopHeartbeat();
        }

        // then
        assertThat(frames).extracting(OutboundMessage::activeCount).containsExactly(7L);
        assertThat(rounds.get()).as("회차가 한 번만 돌았다 — 타이머가 첫 실패에 죽었다").isGreaterThan(1);
    }
}
