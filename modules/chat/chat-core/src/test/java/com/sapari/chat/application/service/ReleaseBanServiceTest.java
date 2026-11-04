package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.command.ReleaseBanCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.global.time.TimeProvider;

/**
 * 밴 해제 — 이 도메인의 <b>첫 역경로</b>가 실제로 역경로인지 잰다.
 *
 * <p>세 쓰기가 한 명령이라 어느 하나만 빠져도 해제가 해제가 아니다. 그중 둘은 여기서 잰다 —
 * <b>순서</b>(정본 → 미러)와 <b>미러 삭제가 조건 없이 돌아가는가</b>. 셋째(사면 기준선이 누적을
 * 접는가)는 SQL 이 만드는 보장이라 {@code ChatBanStateRepositoryImplTest} 가 실제 Postgres 에서 잰다.
 *
 * <p>권한도 여기서 잰다. 해제는 ADMIN 전용이고 그 판정이 틀리면 자동 승격이 요구한
 * <b>서로 다른 판매자 3·6·9명</b>의 독립된 판단을 한 명이 되돌릴 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReleaseBanService — 정본 → 미러, 조건 없이, ADMIN 만")
class ReleaseBanServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T01:00:00Z");

    @Mock
    private ChatBanReleaseRecorder releaseRecorder;
    @Mock
    private ChatBanWriteRepository banWriteRepository;

    private ReleaseBanService service;

    private final UUID targetUserId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReleaseBanService(new ChatPermissionPolicy(), releaseRecorder,
                banWriteRepository, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private ReleaseBanCommand command(String role) {
        return new ReleaseBanCommand(targetUserId, actorId, role);
    }

    @Test
    @DisplayName("⭐ 정본을 먼저 지우고 그 다음 미러를 지운다 — 반대 순서는 밴을 되살린다")
    void deletesTheSourceOfTruthBeforeTheMirror() {
        // given
        given(releaseRecorder.release(targetUserId, actorId, NOW)).willReturn(true);

        // when
        service.release(command("ADMIN"));

        // then: 미러가 먼저 지워지면 남은 정본 행이 다음 강퇴의 조기 반환에서 미러에 다시 써진다
        InOrder order = inOrder(releaseRecorder, banWriteRepository);
        order.verify(releaseRecorder).release(targetUserId, actorId, NOW);
        order.verify(banWriteRepository).releaseMirror(targetUserId);
    }

    @Test
    @DisplayName("⭐ 풀 밴이 없어도 미러를 지운다 — 게이팅하면 재시도가 미러에 닿지 못한다")
    void deletesTheMirrorEvenWhenNoRowWasRemoved() {
        // given: 정본은 이미 지워졌고 미러 삭제만 실패한 뒤의 재시도가 이 모양이다
        given(releaseRecorder.release(targetUserId, actorId, NOW)).willReturn(false);

        // when
        assertThatCode(() -> service.release(command("ADMIN")))
                .as("지울 밴이 없다는 것은 실패가 아니다 — 해제는 멱등이어야 한다")
                .doesNotThrowAnyException();

        // then
        verify(banWriteRepository)
                .releaseMirror(targetUserId);
    }

    @Test
    @DisplayName("미러 삭제가 실패하면 명령도 실패한다 — 미러가 남은 해제는 아무도 풀지 못한 해제다")
    void propagatesMirrorFailure() {
        // given
        given(releaseRecorder.release(targetUserId, actorId, NOW)).willReturn(true);
        willThrow(new IllegalStateException("Redis 장애"))
                .given(banWriteRepository).releaseMirror(targetUserId);

        // when & then: 삼키면 운영자가 "풀었다"고 믿는데 그 사람은 계속 막혀 있다
        assertThatThrownBy(() -> service.release(command("ADMIN")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"USER", "SELLER"})
    @DisplayName("⭐ ADMIN 이 아니면 거부한다 — 판매자 한 명이 3·6·9명의 판단을 되돌릴 수 없다")
    void onlyAdminMayRelease(String role) {
        // when & then
        assertThatThrownBy(() -> service.release(command(role)))
                .isInstanceOf(ChatPermissionDeniedException.class);

        verify(releaseRecorder, never()).release(any(), any(), any());
        verify(banWriteRepository, never()).releaseMirror(any());
    }

    @ParameterizedTest(name = "role={0}")
    @ValueSource(strings = {"OPERATOR", "admin", "", "GUEST"})
    @DisplayName("모르는 역할 이름은 거부한다 — 조용히 구매자로 접으면 역할이 늘 때만 드러난다")
    void unknownRoleIsRejected(String role) {
        // when & then
        assertThatThrownBy(() -> service.release(command(role)))
                .isInstanceOf(ChatPermissionDeniedException.class)
                .hasMessageContaining("알 수 없는 역할");

        verify(releaseRecorder, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("역할이 null 이어도 거부한다 — 인증 주체가 비어 온 경우")
    void nullRoleIsRejected() {
        assertThatThrownBy(() -> service.release(command(null)))
                .isInstanceOf(ChatPermissionDeniedException.class);
    }
}
