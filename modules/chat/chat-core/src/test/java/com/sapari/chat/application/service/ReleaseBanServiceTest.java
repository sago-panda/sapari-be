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
import com.sapari.chat.domain.repository.ChatBanStateRepository.Released;
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
        given(releaseRecorder.release(targetUserId, actorId, NOW))
                .willReturn(new Released(true, null, UUID.randomUUID()));

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
        given(releaseRecorder.release(targetUserId, actorId, NOW))
                .willReturn(new Released(false, null, null));

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
        given(releaseRecorder.release(targetUserId, actorId, NOW))
                .willReturn(new Released(true, null, UUID.randomUUID()));
        willThrow(new IllegalStateException("Redis 장애"))
                .given(banWriteRepository).releaseMirror(targetUserId);

        // when & then: 삼키면 운영자가 "풀었다"고 믿는데 그 사람은 계속 막혀 있다
        assertThatThrownBy(() -> service.release(command("ADMIN")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ⭐ 경합을 409 로 번역한다 — 5xx 로 흘리면 복구 수단이 막힌다.
     *
     * <p>해제의 복구 수단은 <b>멱등 재시도</b>인데, 번역이 없으면 공통 처리기의
     * {@code @ExceptionHandler(Exception.class)} 로 떨어져 {@code log.error} + 500 이 된다.
     * 500 은 "재시도해도 소용없다" 는 신호로 읽히고 정상 혼잡이 알림을 울린다 —
     * {@code ChatErrorCode} 가 409 를 고른 근거가 바로 그것인데, 그 결정이 이 경로에서만 역전돼
     * 있었다. 강퇴 경로는 같은 세 타입을 같은 예외로 접는다({@code KickUserService}).
     *
     * <p>그 창이 열리는 때가 하필 <b>해제를 누르는 가장 현실적인 상황</b>이다 — 지금 집중적으로
     * 강퇴당하는 사람을 푸는 것. 그리고 그 타입이 실제로 나오는 것은 저장소에 이미 실측돼 있다
     * ({@code ChatKickRecorderConcurrencyTest} — 어드바이저리 잠금 + {@code SET LOCAL
     * lock_timeout='1s'} 를 실제 Postgres 에서 태우는 쪽이다).
     *
     * <p>처음에 {@code ChatBanStateRepositoryImplTest.lockContentionSurfacesAsQueryTimeout} 을
     * 인용했는데 <b>다른 기구를 재는 테스트</b>였다 — 그쪽은 유니크 제약 행 대기 +
     * {@code template.setTimeout(1)} 의 문장 취소이고, 해제 경로가 타는 것은 어드바이저리 잠금과
     * {@code SET LOCAL lock_timeout} 이다. 같은 예외 타입이 나와서 맞는 인용처럼 보였다.
     *
     * <p>상한의 <b>값</b>(1s)이 문제가 아니었다. 짧게 하면 운영자가 필요한 그 순간에 실패하고
     * 길게 하면 기다린다. 정상 보유가 밀리초라 1s 는 1000배 여유다 — 빠진 것은 실패의 번역이다.
     */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("contentionFailures")
    @DisplayName("⭐ 잠금 경합·상한 초과는 409 로 번역한다 — 500 이면 재시도 신호가 안 간다")
    void contentionIsTranslatedToARetryableFailure(RuntimeException contention) {
        // given
        org.mockito.BDDMockito.willThrow(contention)
                .given(releaseRecorder).release(targetUserId, actorId, NOW);

        // when & then
        assertThatThrownBy(() -> service.release(command("ADMIN")))
                .isInstanceOf(com.sapari.chat.domain.exception.ChatKickContendedException.class);

        // 미러는 건드리지 않는다 — 정본이 안 바뀌었으므로 지울 것이 없다
        verify(banWriteRepository, never()).releaseMirror(any());
    }

    static java.util.stream.Stream<RuntimeException> contentionFailures() {
        return java.util.stream.Stream.of(
                new org.springframework.dao.PessimisticLockingFailureException("잠금 실패"),
                new org.springframework.dao.QueryTimeoutException("lock_timeout 초과"),
                new org.springframework.transaction.TransactionTimedOutException("트랜잭션 상한"));
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
    @ValueSource(strings = {"OPERATOR", "admin", "GUEST"})
    @DisplayName("모르는 역할 이름은 거부한다 — 조용히 구매자로 접으면 역할이 늘 때만 드러난다")
    void unknownRoleIsRejected(String role) {
        // when & then
        assertThatThrownBy(() -> service.release(command(role)))
                .isInstanceOf(ChatPermissionDeniedException.class)
                .hasMessageContaining("알 수 없는 역할");

        verify(releaseRecorder, never()).release(any(), any(), any());
    }

    /**
     * 빈 값·{@code null} 은 <b>커맨드 경계</b>에서 끊긴다 — 서비스까지 오지 않는다.
     *
     * <p>처음에 이 둘을 위 {@code unknownRoleIsRejected} 에 섞어 두고
     * {@code ChatPermissionDeniedException} 을 기대했는데, 커맨드에 컴팩트 생성자를 채우면서
     * 더 앞에서 끊기게 됐다. 같은 불변식이 두 레이어에 갈려 있던 것을 모은 결과라 이쪽이 맞고,
     * 여기서는 <b>어디서 끊기는지</b>를 그대로 적어 둔다 — "거부된다" 만 재면 다음 사람이 그
     * 생성자를 지워도 서비스가 받아 주는 바람에 초록으로 남는다.
     */
    @ParameterizedTest(name = "role=[{0}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("빈 역할은 커맨드 생성에서 거부된다 — 서비스까지 오지 않는다")
    void blankRoleIsRejectedAtTheCommandBoundary(String role) {
        assertThatThrownBy(() -> command(role))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actorRole");
    }

    @Test
    @DisplayName("역할이 null 이어도 커맨드 생성에서 거부된다")
    void nullRoleIsRejectedAtTheCommandBoundary() {
        assertThatThrownBy(() -> command(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
