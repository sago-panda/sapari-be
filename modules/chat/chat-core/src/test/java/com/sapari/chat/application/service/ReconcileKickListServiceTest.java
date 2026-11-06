package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.command.ReconcileKickListCommand;
import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.repository.ChatKickWriteRepository;
import com.sapari.chat.application.service.ChatAuditRecorder;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

/**
 * 강퇴 집행 명단의 재적재 — <b>정본이 입력</b>이라는 계약을 잰다.
 *
 * <p>Redis 쪽 성질(교체·멱등·WRONGTYPE 복구·만료 제거)은 실제 Redis 가 만드는 보장이라
 * {@code ChatKickWriteRedisRepositoryTest} 가 잰다. 여기서 재는 것은 둘이다 —
 * 권한, 그리고 <b>쓰는 값이 정본에서 읽은 그것인지</b>.
 *
 * <p>둘째가 핵심이다. 명령에 명단을 실을 자리가 없고 서비스가 정본을 읽어 넘기므로, 이 경로로는
 * 정본에 없는 사람을 집행 명단에 올릴 수 없다 — 복구 수단이 <b>감사 없는 강퇴 수단</b>이 되지
 * 않게 하는 것이 그 계약의 값이다. 그 사실은 "정본에서 읽은 목록을 그대로 넘긴다" 를 박아야만
 * 지켜진다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconcileKickListService — 정본이 입력이고 ADMIN 만 부른다")
class ReconcileKickListServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");

    @Mock
    private GetLiveRoomUseCase liveRoomReader;
    @Mock
    private ChatKickLogRepository kickLogRepository;
    @Mock
    private ChatKickWriteRepository kickWriteRepository;
    @Mock
    private ChatAuditRecorder auditRecorder;

    private ReconcileKickListService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReconcileKickListService(
                new ChatPermissionPolicy(), liveRoomReader, kickLogRepository, kickWriteRepository,
                auditRecorder, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
        // 기본은 "진행 중인 방". 상류 게이트에 닿는 모든 테스트가 이걸 쓰지만 권한에서 끝나는
        // 테스트는 안 쓰므로 lenient 다.
        org.mockito.Mockito.lenient().when(liveRoomReader.findRoom(roomId))
                .thenReturn(java.util.Optional.of(
                        new LiveRoomView(roomId, UUID.randomUUID(), true, java.time.Instant.EPOCH)));
    }

    private ReconcileKickListCommand command(String role) {
        return new ReconcileKickListCommand(roomId, actorId, role);
    }

    @Test
    @DisplayName("⭐ 정본에서 읽은 명단을 그대로 넘긴다 — 이 경로로는 정본에 없는 사람을 올릴 수 없다")
    void writesExactlyWhatTheSourceOfTruthSays() {
        // given
        List<UUID> fromSourceOfTruth = List.of(UUID.randomUUID(), UUID.randomUUID());
        given(kickLogRepository.findKickedUserIds(roomId)).willReturn(fromSourceOfTruth);

        // when
        var outcome = service.reconcile(command("ADMIN"));

        // then
        assertThat(outcome.restored()).isEqualTo(2);
        verify(kickWriteRepository).reconcile(eq(roomId), eq(fromSourceOfTruth));
    }

    /**
     * ⭐ 재적재가 감사 한 줄을 남긴다. {@code detail} 의 두 수가 이 조치의 전부다 — 미러는 과거를
     * 들고 있지 않아서 "몇 명을 되깔았나" 와 "키가 우리 타입이 아니었나(= 집행이 꺼져 있었다)" 를
     * 이 행 말고 어디서도 읽을 수 없다.
     *
     * <p>이 경로는 DB 트랜잭션이 없어 감사가 자기 경계를 가진다 — 그래서 <b>조치가 성공한 뒤</b>에
     * 적고, 순서가 뒤집히면 일어나지 않은 재적재가 기록에 남는다.
     */
    @Test
    @DisplayName("⭐ 재적재가 감사 한 줄을 남긴다 — 되깐 수와 오염 여부가 그 행에만 있다")
    void aSuccessfulReconcileIsAudited() {
        // given: 오염된 키를 고쳤다
        given(kickLogRepository.findKickedUserIds(roomId))
                .willReturn(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        given(kickWriteRepository.reconcile(eq(roomId), any())).willReturn(true);

        // when
        service.reconcile(command("ADMIN"));

        // then
        ArgumentCaptor<ChatAuditEntry> captor = ArgumentCaptor.forClass(ChatAuditEntry.class);
        InOrder order = inOrder(kickWriteRepository, auditRecorder);
        order.verify(kickWriteRepository).reconcile(eq(roomId), any());
        order.verify(auditRecorder).record(captor.capture());
        // 횟수를 박는다. ⚠️ 위 순서 단언만으로는 <쓰기 앞에 한 줄 더 적는> 변경을 못 잡는다 —
        // 실측: 재적재 전에 append 를 하나 더 넣으면 뒤의 호출이 그 순서를 만족시켜 11건 전부
        // 초록이었다. 순서는 자료 의존으로도 강제된다(wasCorrupted 는 쓰기 뒤에만 있다).
        verify(auditRecorder, times(1)).record(any());

        ChatAuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(ChatAuditAction.CHAT_RECONCILE_KICK_LIST);
        assertThat(entry.actorId()).isEqualTo(actorId);
        assertThat(entry.actorRole()).isEqualTo(ChatRole.ADMIN);
        assertThat(entry.targetId()).isEqualTo(roomId);
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(entry.detail())
                .containsEntry("restored", "3")
                .containsEntry("wasCorrupted", "true");
    }

    /** 거부된 요청은 감사에 남지 않는다 — 조치가 수행되지 않았다. */
    @Test
    @DisplayName("거부는 감사에 남지 않는다 — 끝난 방·권한 없음 모두")
    void aRefusalIsNotAudited() {
        // given: 끝난 방
        given(liveRoomReader.findRoom(roomId)).willReturn(java.util.Optional.of(
                new LiveRoomView(roomId, UUID.randomUUID(), false, java.time.Instant.EPOCH)));

        // when & then
        assertThatThrownBy(() -> service.reconcile(command("ADMIN")))
                .isInstanceOf(com.sapari.chat.domain.exception.LiveNotActiveException.class);
        assertThatThrownBy(() -> service.reconcile(command("SELLER")))
                .isInstanceOf(ChatPermissionDeniedException.class);

        verify(auditRecorder, never()).record(any());
    }

    @Test
    @DisplayName("⭐ 정본이 비어도 성공이고 0 을 돌려준다 — '복구 실패' 와 '복구할 것이 없음' 은 다르다")
    void anEmptySourceOfTruthIsASuccess() {
        // given
        given(kickLogRepository.findKickedUserIds(roomId)).willReturn(List.of());

        // when
        var outcome = service.reconcile(command("ADMIN"));

        // then: 실패로 가르면 운영자가 둘을 구분할 수 없다
        assertThat(outcome.restored()).isZero();
        verify(kickWriteRepository).reconcile(eq(roomId), eq(List.of()));
    }

    /**
     * ⭐ 오염 사실이 결과로 나와야 한다.
     *
     * <p>{@code DEL} 뒤에는 "키가 타입이 틀려 있었다" 를 알 길이 없는데, 그 사실이 <b>그 방의 강퇴
     * 집행이 꺼져 있었다는 직접 증거</b>다. 이 조치를 ADMIN 전용으로 둔 근거가 가청성이었으므로
     * 복원 수만 돌려주면 가장 센 신호가 사라진다.
     */
    @Test
    @DisplayName("⭐ 고치기 전 오염 여부가 결과에 실린다 — 집행이 꺼져 있었다는 직접 증거다")
    void theCorruptionFactSurvivesTheRepair() {
        // given
        given(kickLogRepository.findKickedUserIds(roomId)).willReturn(List.of(UUID.randomUUID()));
        given(kickWriteRepository.reconcile(eq(roomId), any())).willReturn(true);

        // when
        var outcome = service.reconcile(command("ADMIN"));

        // then
        assertThat(outcome.wasCorrupted())
                .as("오염이었다는 사실이 사라지면 운영자는 조치가 필요했는지도 모른다")
                .isTrue();
    }

    /**
     * ⭐ 끝난 방에는 재적재하지 않는다 — <b>회수 만료가 걷히고 다시 붙여 줄 경로가 없다.</b>
     *
     * <p>{@code chat:kicked:} 에 만료를 붙이는 경로는 하나뿐이고({@code expireAfterRoomEnded},
     * RoomEnded 수신 시) {@code Ended} 는 terminal 이라 그 신호가 다시 오지 않는다. 그래서 끝난
     * 방에 더하면 {@code PERSIST} 가 만료를 걷어내고 그 키가 Redis 에 영구히 남는다.
     *
     * <p>처방이 이미 저장소에 적혀 있었다 — {@code ChatKickWriteRepository.register} javadoc 이
     * 대칭 케이스("늦은 등록")를 두고 "막을 자리는 여기가 아니라 <b>상류</b>다: 강퇴 유스케이스가
     * 방을 정본에서 읽어 진행 중이 아니면 거부하면 된다" 고 적었다. 강퇴는 그 게이트를 갖는데
     * 재적재는 없었다.
     *
     * <p>이것은 <b>권한이 아니다</b> — 재적재는 방 소유와 무관한 ADMIN 전용이고 그 판정은 앞에서
     * 끝난다. 여기서 방을 읽는 것은 "되살릴 집행이 있는 방인가" 를 묻는 것이다.
     */
    @Test
    @DisplayName("⭐ 끝난 방은 거부한다 — 되살리면 회수 만료가 영구히 걷힌다")
    void refusesWhenTheRoomHasEnded() {
        // given
        given(liveRoomReader.findRoom(roomId)).willReturn(java.util.Optional.of(
                new LiveRoomView(roomId, UUID.randomUUID(), false, java.time.Instant.EPOCH)));

        // when & then
        assertThatThrownBy(() -> service.reconcile(command("ADMIN")))
                .isInstanceOf(com.sapari.chat.domain.exception.LiveNotActiveException.class);

        verify(kickWriteRepository, never()).reconcile(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"USER", "SELLER"})
    @DisplayName("⭐ ADMIN 이 아니면 거부한다 — 정본도 읽지 않는다")
    void onlyAdminMayReconcile(String role) {
        // when & then
        assertThatThrownBy(() -> service.reconcile(command(role)))
                .isInstanceOf(ChatPermissionDeniedException.class);

        verify(kickLogRepository, never()).findKickedUserIds(any());
        verify(kickWriteRepository, never()).reconcile(any(), any());
    }

    @ParameterizedTest(name = "role={0}")
    @ValueSource(strings = {"OPERATOR", "admin", ""})
    @DisplayName("모르는 역할 이름은 거부한다")
    void unknownRoleIsRejected(String role) {
        assertThatThrownBy(() -> service.reconcile(command(role)))
                .isInstanceOf(ChatPermissionDeniedException.class)
                .hasMessageContaining("알 수 없는 역할");
    }
}
