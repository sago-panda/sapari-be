package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatWithdrawalRepository;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;
import com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 입장 게이트가 열린 사실이 밖으로 나가는지 본다.
 *
 * <p>이 자리의 신호는 두 겹으로 가려져 있었다 — 카운터가 프로세스 안에 있고, 로그는 사유당 간격으로
 * 솎아낸다. 솎인 쪽은 아예 남지 않으므로 <b>짧게 반복되는 장애는 흔적이 없다</b>. 게이트가 열린 동안
 * 제재받은 사람이 방에 들어오는데, 그 구간에 알림을 걸 방법이 없었다.
 *
 * <p>세 판정을 한 지표의 다른 태그로 두되 <b>checkpoint 를 나눈다</b> — 입장에서만 열렸다면 그 사람은
 * 들어와서 보고만 있고, 전송 재확인까지 열렸다면 끝난 방에 글이 들어온다.
 */
@DisplayName("입장 게이트 관측 — 게이트가 열리면 지표가 오른다")
class EntryGateMetricsTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private ChatKickRepository kickRepository;
    private ChatRoomEndedRepository roomEndedRepository;
    private ChatBanRepository banRepository;
    private ChatWithdrawalRepository withdrawalRepository;
    private MeterRegistry registry;
    private EntryGate gate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kickRepository = mock(ChatKickRepository.class);
        roomEndedRepository = mock(ChatRoomEndedRepository.class);
        banRepository = mock(ChatBanRepository.class);
        withdrawalRepository = mock(ChatWithdrawalRepository.class);
        given(withdrawalRepository.isWithdrawing(any())).willReturn(Mono.just(false));
        registry = new SimpleMeterRegistry();
        gate = new EntryGate(kickRepository, roomEndedRepository, banRepository, withdrawalRepository,
                new MicrometerChatMetrics(registry), mock(ChatSessionRegistry.class));
    }

    private ChatSession session() {
        return new ChatSession(roomId, userId, ChatRole.BUYER, "닉", "e@example.com", false);
    }

    private double open(String policy, String checkpoint, String failure) {
        Counter counter = registry.find("chat.policy.open")
                .tags("policy", policy, "checkpoint", checkpoint, "failure", failure).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("⭐ 종료 마커 조회가 실패해 입장시키면 policy=room_ended checkpoint=entry 가 오른다")
    void roomEndedReadFailureIsReported() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.error(new IllegalStateException("Redis down")));
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        given(banRepository.isBanned(userId)).willReturn(Mono.just(false));

        // when: fail-open 이라 입장은 통과한다
        StepVerifier.create(gate.verify(session())).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(open("room_ended", "entry", "unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 강퇴 조회가 실패해 입장시키면 policy=kick 이 오른다")
    void kickReadFailureIsReported() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
        given(kickRepository.isKicked(roomId, userId))
                .willReturn(Mono.error(new IllegalStateException("Redis down")));
        given(banRepository.isBanned(userId)).willReturn(Mono.just(false));

        // when
        StepVerifier.create(gate.verify(session())).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(open("kick", "entry", "unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 강퇴 명단의 키 타입이 어긋나면 failure=store_corrupted 로 갈린다 — 사람이 치울 때까지 안 낫는다")
    void aCorruptedKickSetIsASeparateSeries() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
        given(kickRepository.isKicked(roomId, userId))
                .willReturn(Mono.error(new KickStoreCorruptedException("chat:kicked:" + roomId, null)));
        given(banRepository.isBanned(userId)).willReturn(Mono.just(false));

        // when
        StepVerifier.create(gate.verify(session())).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(open("kick", "entry", "store_corrupted")).isEqualTo(1);
        assertThat(open("kick", "entry", "unavailable"))
                .as("한 통에 담으면 영영 안 낫는 상태가 곧 복구될 장애에 묻힌다").isZero();
    }

    @Test
    @DisplayName("⭐ 밴 조회가 실패해 입장시키면 policy=ban 이 오른다")
    void banReadFailureIsReported() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        given(banRepository.isBanned(userId)).willReturn(Mono.error(new IllegalStateException("Redis down")));

        // when
        StepVerifier.create(gate.verify(session())).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(open("ban", "entry", "unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 탈퇴 기록 조회가 실패해 입장시키면 policy=withdrawing 이 오른다")
    void withdrawalReadFailureIsReported() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        given(banRepository.isBanned(userId)).willReturn(Mono.just(false));
        given(withdrawalRepository.isWithdrawing(userId)).willReturn(Mono.error(new IllegalStateException("Redis down")));

        // when
        StepVerifier.create(gate.verify(session())).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(open("withdrawing", "entry", "unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 전송 재확인이 열리면 checkpoint=send 다 — 입장과 합치면 끝난 방에 글이 들어온 사실이 묻힌다")
    void theSendPathRecheckIsASeparateCheckpoint() {
        // given: 재확인 창이 열려 있고 그 조회가 실패한다
        ChatSessionRegistry registry = mock(ChatSessionRegistry.class);
        given(registry.isRoomKnownEnded("s1")).willReturn(false);
        given(registry.shouldRecheckRoomAlive("s1")).willReturn(true);
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.error(new IllegalStateException("Redis down")));
        EntryGate sendGate = new EntryGate(kickRepository, roomEndedRepository, banRepository,
                withdrawalRepository, new MicrometerChatMetrics(this.registry), registry);

        // when: fail-open 이라 전송은 통과한다
        StepVerifier.create(sendGate.isRoomAlive("s1", session())).expectNext(true).expectComplete().verify(VERIFY_CEILING);

        // then: 입장 쪽이 아니라 전송 쪽으로 세어진다
        assertThat(open("room_ended", "send", "unavailable")).isEqualTo(1);
        assertThat(open("room_ended", "entry", "unavailable"))
                .as("두 자리는 잃는 것이 다르다 — 입장만 열렸다면 그 사람은 보고만 있다").isZero();
    }

    @Test
    @DisplayName("게이트가 다 닫혀 있으면 지표가 아예 생기지 않는다 — 평상시 0 이어야 알림을 걸 수 있다")
    void aHealthyGateReportsNothing() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        given(banRepository.isBanned(userId)).willReturn(Mono.just(false));

        // when
        StepVerifier.create(gate.verify(session())).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(registry.find("chat.policy.open").counters()).isEmpty();
    }
}
