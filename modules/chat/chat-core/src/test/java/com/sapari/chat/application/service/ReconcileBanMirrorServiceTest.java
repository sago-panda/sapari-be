package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatAccountEventPublisher;
import com.sapari.chat.command.ReconcileBanMirrorCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.model.VersionedBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository;
import com.sapari.chat.domain.repository.ChatBanWriteRepository.MirrorApply;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.port.ReconcileBanMirrorUseCase.ReconcileOutcome;
import com.sapari.global.time.TimeProvider;

/**
 * 밴 미러 재적재 — <b>정본이 입력</b>이고, 어긋남을 센다.
 *
 * <p>Redis 쪽 성질(번호 거절·늘리기 전용·결과 구분)은 {@code ChatBanWriteRedisRepositoryTest} 가 실제
 * Redis 에서 잰다. 여기서 재는 것은 권한, 쓰는 값이 잠금 안에서 읽은 그것인지, 그리고 "새로 씀" 만을
 * 어긋남으로 세는지다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconcileBanMirrorService — 정본이 입력이고 ADMIN 만 부른다")
class ReconcileBanMirrorServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ChatBanStateRepository banStateRepository;
    @Mock
    private ChatBanMirrorSnapshotter snapshotter;
    @Mock
    private ChatBanWriteRepository banWriteRepository;
    @Mock
    private ChatAuditRecorder auditRecorder;
    @Mock
    private ChatAccountEventPublisher accountEventPublisher;

    private final UUID actorId = UUID.randomUUID();
    private ReconcileBanMirrorService service;

    @BeforeEach
    void setUp() {
        TimeProvider clock = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ReconcileBanMirrorService(new ChatPermissionPolicy(), banStateRepository, snapshotter,
                banWriteRepository, auditRecorder, accountEventPublisher, clock);
    }

    private ReconcileBanMirrorCommand command(String role) {
        return new ReconcileBanMirrorCommand(actorId, role);
    }

    private VersionedBan banOf(UUID userId, Instant expiresAt, long version) {
        return new VersionedBan(new ChatBan(userId, SYSTEM, expiresAt, NOW.minus(Duration.ofDays(1))), version);
    }

    @Test
    @DisplayName("⭐ 잠금 안에서 읽은 밴과 번호를 그대로 미러에 싣는다")
    void writesWhatTheLockedSnapshotRead() {
        // given
        UUID user = UUID.randomUUID();
        Instant expiry = NOW.plus(Duration.ofDays(30));
        given(banStateRepository.findActiveUserIds(NOW)).willReturn(List.of(user));
        given(snapshotter.snapshot(user, NOW)).willReturn(Optional.of(banOf(user, expiry, 31L)));
        given(banWriteRepository.ban(user, expiry, NOW, 31L)).willReturn(MirrorApply.UNCHANGED);

        // when
        service.reconcile(command("ADMIN"));

        // then
        verify(banWriteRepository).ban(user, expiry, NOW, 31L);
    }

    @Test
    @DisplayName("⭐ 미러에 없어서 새로 쓴 것만 어긋남으로 센다 — 연장은 맞는 미러에서도 지연차로 나와 뜻이 없다")
    void onlyCreatedCountsAsRestored() {
        // given: 넷 중 하나만 미러에 없었다
        List<UUID> users = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<MirrorApply> results = List.of(
                MirrorApply.CREATED, MirrorApply.EXTENDED, MirrorApply.UNCHANGED, MirrorApply.STALE);
        given(banStateRepository.findActiveUserIds(NOW)).willReturn(users);
        for (int i = 0; i < users.size(); i++) {
            UUID user = users.get(i);
            given(snapshotter.snapshot(user, NOW)).willReturn(Optional.of(banOf(user, null, i + 1)));
            given(banWriteRepository.ban(user, null, NOW, i + 1)).willReturn(results.get(i));
        }

        // when
        ReconcileOutcome outcome = service.reconcile(command("ADMIN"));

        // then
        assertThat(outcome).isEqualTo(new ReconcileOutcome(4, 1));
    }

    /**
     * ⭐ 번호에 지지 않은 밴마다 계정 밴 이벤트를 낸다.
     *
     * <p>재적재가 강퇴보다 먼저 미러에 닿으면 강퇴 쪽 쓰기는 늦은 번호로 거절되고 강퇴는 이벤트를 건너뛴다.
     * 그 사람의 열린 세션을 끊는 것이 이 이벤트뿐이라, 새로 썼든 이미 있었든 낸다. 늦은 번호는 그 사이에
     * 해제가 지나갔다는 뜻이라 내지 않는다.
     */
    @Test
    @DisplayName("⭐ 늦은 번호가 아닌 밴마다 계정 밴 이벤트를 낸다 — 재적재가 강퇴를 앞지르면 세션을 끊는 것이 이것뿐이다")
    void publishesBannedForEveryBanNotRejectedAsStale() {
        // given
        List<UUID> users = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<MirrorApply> results = List.of(
                MirrorApply.CREATED, MirrorApply.EXTENDED, MirrorApply.UNCHANGED, MirrorApply.STALE);
        given(banStateRepository.findActiveUserIds(NOW)).willReturn(users);
        for (int i = 0; i < users.size(); i++) {
            UUID user = users.get(i);
            given(snapshotter.snapshot(user, NOW)).willReturn(Optional.of(banOf(user, null, i + 1)));
            given(banWriteRepository.ban(user, null, NOW, i + 1)).willReturn(results.get(i));
        }

        // when
        service.reconcile(command("ADMIN"));

        // then
        then(accountEventPublisher).should().publishBanned(users.get(0));
        then(accountEventPublisher).should().publishBanned(users.get(1));
        then(accountEventPublisher).should().publishBanned(users.get(2));
        then(accountEventPublisher).should(never()).publishBanned(users.get(3));
    }

    @Test
    @DisplayName("목록을 읽은 뒤 풀리거나 만료된 밴은 쓰지 않는다 — 잠금 안에서 다시 읽은 것이 기준이다")
    void aBanGoneByTheTimeOfTheLockIsSkipped() {
        // given
        UUID user = UUID.randomUUID();
        given(banStateRepository.findActiveUserIds(NOW)).willReturn(List.of(user));
        given(snapshotter.snapshot(user, NOW)).willReturn(Optional.empty());

        // when
        ReconcileOutcome outcome = service.reconcile(command("ADMIN"));

        // then
        assertThat(outcome).isEqualTo(new ReconcileOutcome(0, 0));
        verify(banWriteRepository, never()).ban(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("감사 한 줄에 유효 수와 복원 수가 남는다 — 대상이 집합이라 target_id 는 비운다")
    void auditsTheCounts() {
        // given
        UUID user = UUID.randomUUID();
        given(banStateRepository.findActiveUserIds(NOW)).willReturn(List.of(user));
        given(snapshotter.snapshot(user, NOW)).willReturn(Optional.of(banOf(user, null, 5L)));
        given(banWriteRepository.ban(user, null, NOW, 5L)).willReturn(MirrorApply.CREATED);

        // when
        service.reconcile(command("ADMIN"));

        // then
        ArgumentCaptor<ChatAuditEntry> captor = ArgumentCaptor.forClass(ChatAuditEntry.class);
        verify(auditRecorder).record(captor.capture());
        ChatAuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(ChatAuditAction.CHAT_RECONCILE_BAN_MIRROR);
        assertThat(entry.targetId()).isNull();
        assertThat(entry.detail()).containsEntry("active", "1").containsEntry("restored", "1");
    }

    @Test
    @DisplayName("미러 쓰기가 실패하면 그대로 던진다 — 삼키면 운영자가 복구됐다고 믿는다")
    void propagatesAMirrorFailure() {
        // given
        UUID user = UUID.randomUUID();
        given(banStateRepository.findActiveUserIds(NOW)).willReturn(List.of(user));
        given(snapshotter.snapshot(user, NOW)).willReturn(Optional.of(banOf(user, null, 5L)));
        given(banWriteRepository.ban(user, null, NOW, 5L)).willThrow(new IllegalStateException("Redis 장애"));

        // when & then
        assertThatThrownBy(() -> service.reconcile(command("ADMIN"))).isInstanceOf(IllegalStateException.class);
        verify(auditRecorder, never()).record(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SELLER"})
    @DisplayName("ADMIN 이 아니면 정본을 읽기 전에 거부한다")
    void nonAdminIsRejectedBeforeReading(String role) {
        assertThatThrownBy(() -> service.reconcile(command(role)))
                .isInstanceOf(ChatPermissionDeniedException.class);
        verify(banStateRepository, never()).findActiveUserIds(any());
    }
}
