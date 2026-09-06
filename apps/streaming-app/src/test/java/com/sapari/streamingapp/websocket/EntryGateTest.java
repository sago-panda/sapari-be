package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EntryGateTest {

    private ChatKickRepository kickRepository;
    private ChatRoomEndedRepository roomEndedRepository;
    private ChatBanRepository banRepository;
    private ChatSessionRegistry registry;
    private EntryGate gate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kickRepository = mock(ChatKickRepository.class);
        roomEndedRepository = mock(ChatRoomEndedRepository.class);
        banRepository = mock(ChatBanRepository.class);
        registry = mock(ChatSessionRegistry.class);
        gate = new EntryGate(kickRepository, roomEndedRepository, banRepository, registry);
    }

    /** 방이 살아있다는 전제 — 종료 검사를 통과시킨다. */
    private void givenRoomAlive() {
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
    }

    /** 밴이 아니라는 전제 — 강퇴 검사만 보고 싶을 때 뒤 단계를 열어 둔다. */
    private void givenNotBanned() {
        given(banRepository.isBanned(userId)).willReturn(Mono.just(false));
    }

    private ChatSession member() {
        return new ChatSession(roomId, userId, ChatRole.BUYER, "닉", "e@example.com", false);
    }

    @Test
    @DisplayName("강퇴 아님 → 통과(빈 완료)")
    void not_kicked_passes() {
        // given
        givenRoomAlive();
        givenNotBanned();
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));

        // when & then
        StepVerifier.create(gate.verify(member())).verifyComplete();
    }

    @Test
    @DisplayName("강퇴됨 → EntryDeniedException(KICKED)")
    void kicked_denied() {
        // given
        givenRoomAlive();
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(true));

        // when & then
        StepVerifier.create(gate.verify(member()))
                .expectErrorMatches(e -> e instanceof EntryDeniedException ed
                        && ed.reason() == EntryDeniedException.Reason.KICKED)
                .verify();
    }

    @Test
    @DisplayName("kicked 조회 Redis 에러 → fail-open(입장 허용)")
    void redis_error_fails_open() {
        // given
        givenRoomAlive();
        givenNotBanned();
        given(kickRepository.isKicked(roomId, userId))
                .willReturn(Mono.error(new RuntimeException("redis down")));

        // when & then
        StepVerifier.create(gate.verify(member())).verifyComplete();
    }

    @Test
    @DisplayName("키 타입 충돌도 fail-open이지만 WARN이 아니라 ERROR — 이 게이트는 사람이 올 때까지 계속 열려 있다")
    void kickStoreCorrupted_failsOpenWithErrorLevel() {
        // given: Redis가 되살아나도 낫지 않는 실패
        givenRoomAlive();
        givenNotBanned();
        String key = "chat:kicked:" + roomId;
        given(kickRepository.isKicked(roomId, userId))
                .willReturn(Mono.error(new KickStoreCorruptedException(key, new RuntimeException("WRONGTYPE"))));

        Logger logger = (Logger) LoggerFactory.getLogger(EntryGate.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when: 가용성 우선 — 입장은 그대로 허용한다
        try {
            StepVerifier.create(gate.verify(member())).verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }

        // then: 곧 복구될 장애(WARN)와 등급이 갈린다. 분기를 지우면 WARN 한 줄만 남아 이 단언이 깨진다
        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("재시도로 낫지 않으니")
                            .contains(key);
                });
    }

    @Test
    @DisplayName("밴이면 거부 — 방과 무관하게 어디도 못 들어온다")
    void banned_denied() {
        // given: 이 방에서 강퇴된 적은 없다. 밴은 방이 아니라 사람에게 걸린다
        givenRoomAlive();
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        given(banRepository.isBanned(userId)).willReturn(Mono.just(true));

        // when & then
        StepVerifier.create(gate.verify(member()))
                .expectErrorMatches(e -> e instanceof EntryDeniedException ed
                        && ed.reason() == EntryDeniedException.Reason.BANNED)
                .verify();
    }

    @Test
    @DisplayName("게스트 → 밴 검사도 건너뛴다 — 접속마다 id가 새로 나 명단이 의미 없다")
    void guest_skips_ban_check() {
        // given
        givenRoomAlive();
        ChatSession guest = new ChatSession(roomId, userId, ChatRole.GUEST, null, null, false);

        // when
        StepVerifier.create(gate.verify(guest)).verifyComplete();

        // then
        then(banRepository).should(never()).isBanned(any());
    }

    @Test
    @DisplayName("밴 조회 Redis 에러 → fail-open(입장 허용) — 종료·강퇴와 같은 저울질")
    void ban_lookup_error_fails_open() {
        // given: Redis가 죽었다고 정상 사용자 전원이 채팅을 잃는 쪽을 택하지 않는다
        givenRoomAlive();
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        given(banRepository.isBanned(userId)).willReturn(Mono.error(new RuntimeException("redis down")));

        // when & then
        StepVerifier.create(gate.verify(member())).verifyComplete();
    }

    @Test
    @DisplayName("강퇴로 이미 거부되면 밴은 묻지 않는다 — 앞에서 걸리면 왕복이 줄어든다")
    void kicked_denial_skips_ban_lookup() {
        // given
        givenRoomAlive();
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(true));

        // when
        StepVerifier.create(gate.verify(member()))
                .expectErrorMatches(e -> e instanceof EntryDeniedException ed
                        && ed.reason() == EntryDeniedException.Reason.KICKED)
                .verify();

        // then
        then(banRepository).should(never()).isBanned(any());
    }

    @Test
    @DisplayName("게스트 → 강퇴 검사 건너뛰고 통과")
    void guest_skips_kick_check() {
        // given
        givenRoomAlive();
        ChatSession guest = new ChatSession(roomId, userId, ChatRole.GUEST, null, null, false);

        // when
        StepVerifier.create(gate.verify(guest)).verifyComplete();

        // then
        then(kickRepository).should(never()).isKicked(any(), any());
    }

    @Test
    @DisplayName("종료된 방 → EntryDeniedException(ROOM_ENDED), 강퇴 조회까지 가지 않는다")
    void ended_room_denied_before_kick_check() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(true));

        // when
        StepVerifier.create(gate.verify(member()))
                .expectErrorMatches(e -> e instanceof EntryDeniedException ed
                        && ed.reason() == EntryDeniedException.Reason.ROOM_ENDED)
                .verify();

        // then
        then(kickRepository).should(never()).isKicked(any(), any());
    }

    @Test
    @DisplayName("종료된 방은 게스트도 막는다 — 끝난 방은 볼 것이 없다")
    void ended_room_denies_guest_too() {
        // given
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(true));
        ChatSession guest = new ChatSession(roomId, userId, ChatRole.GUEST, null, null, false);

        // when & then
        StepVerifier.create(gate.verify(guest))
                .expectErrorMatches(e -> e instanceof EntryDeniedException ed
                        && ed.reason() == EntryDeniedException.Reason.ROOM_ENDED)
                .verify();
    }

    @Test
    @DisplayName("종료 마커 조회 Redis 에러 → fail-open(입장 허용) — 강퇴 조회와 같은 정책")
    void ended_lookup_error_fails_open() {
        // given
        given(roomEndedRepository.isEnded(roomId))
                .willReturn(Mono.error(new RuntimeException("redis down")));
        given(kickRepository.isKicked(roomId, userId)).willReturn(Mono.just(false));
        givenNotBanned();

        // when & then
        StepVerifier.create(gate.verify(member())).verifyComplete();
    }

    @Test
    @DisplayName("재확인 — 창이 닫혀 있으면 Redis에 묻지 않고 살아있는 것으로 본다")
    void isRoomAlive_SkipsRedisWhileWindowClosed() {
        // given
        given(registry.shouldRecheckRoomAlive("s1")).willReturn(false);

        // when & then
        StepVerifier.create(gate.isRoomAlive("s1", member())).expectNext(true).verifyComplete();
        then(roomEndedRepository).should(never()).isEnded(roomId);
    }

    @Test
    @DisplayName("재확인 — 창이 열리면 마커를 읽고, 종료된 방이면 false를 돌려준다")
    void isRoomAlive_ReadsMarkerWhenWindowOpens() {
        // given: 종료 신호를 놓친 Pod라 세션은 아직 살아 있는 상황
        given(registry.shouldRecheckRoomAlive("s1")).willReturn(true);
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(true));

        // when & then: 전송이 NOT_ACTIVE로 거부되어 끝난 방에 이력이 쌓이지 않는다
        StepVerifier.create(gate.isRoomAlive("s1", member())).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("재확인 — 조회 실패는 입장 게이트와 같은 fail-open(전송 허용)")
    void isRoomAlive_FailsOpen() {
        // given
        given(registry.shouldRecheckRoomAlive("s1")).willReturn(true);
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.error(new RuntimeException("redis down")));

        // when & then: Redis가 죽었다고 채팅이 통째로 멈추는 쪽이 더 나쁜 결과다
        StepVerifier.create(gate.isRoomAlive("s1", member())).expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("재확인 — 종료를 확인하면 세션에 표시해 이후 프레임이 창을 타고 빠져나가지 못하게 한다")
    void isRoomAlive_MarksSessionOnEnded() {
        // given
        given(registry.shouldRecheckRoomAlive("s1")).willReturn(true);
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(true));

        // when
        StepVerifier.create(gate.isRoomAlive("s1", member())).expectNext(false).verifyComplete();

        // then: 표시하지 않으면 다음 30초 동안의 프레임이 전부 통과해 이력에 쌓인다
        then(registry).should(times(1)).markRoomEnded("s1");
    }

    @Test
    @DisplayName("재확인 — 이미 종료로 표시된 세션은 Redis에 묻지 않고 곧장 막는다")
    void isRoomAlive_ShortCircuitsWhenAlreadyEnded() {
        // given
        given(registry.isRoomKnownEnded("s1")).willReturn(true);

        // when & then
        StepVerifier.create(gate.isRoomAlive("s1", member())).expectNext(false).verifyComplete();
        then(roomEndedRepository).should(never()).isEnded(roomId);
        then(registry).should(never()).shouldRecheckRoomAlive("s1");
    }
}
