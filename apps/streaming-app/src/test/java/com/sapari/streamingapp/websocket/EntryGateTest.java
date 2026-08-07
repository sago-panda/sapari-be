package com.sapari.streamingapp.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EntryGateTest {

    private ChatKickRepository kickRepository;
    private ChatRoomEndedRepository roomEndedRepository;
    private EntryGate gate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kickRepository = mock(ChatKickRepository.class);
        roomEndedRepository = mock(ChatRoomEndedRepository.class);
        gate = new EntryGate(kickRepository, roomEndedRepository);
    }

    /** 방이 살아있다는 전제 — 종료 검사를 통과시킨다. */
    private void givenRoomAlive() {
        given(roomEndedRepository.isEnded(roomId)).willReturn(Mono.just(false));
    }

    private ChatSession member() {
        return new ChatSession(roomId, userId, ChatRole.BUYER, "닉", "e@example.com", false);
    }

    @Test
    @DisplayName("강퇴 아님 → 통과(빈 완료)")
    void not_kicked_passes() {
        // given
        givenRoomAlive();
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
        given(kickRepository.isKicked(roomId, userId))
                .willReturn(Mono.error(new RuntimeException("redis down")));

        // when & then
        StepVerifier.create(gate.verify(member())).verifyComplete();
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

        // when & then
        StepVerifier.create(gate.verify(member())).verifyComplete();
    }
}
