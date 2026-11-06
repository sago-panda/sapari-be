package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatBanStateRepository.Released;

/**
 * 밴 해제가 남기는 <b>감사 행의 내용</b>을 고정한다.
 *
 * <p>이것이 지키는 것은 하나다 — 지운 밴의 내용(영구였는지·누가 걸었는지)은 정본 행이 사라지므로
 * <b>이 행 말고는 어디에도 남지 않는다</b>. {@code ChatBanStateRepository.release} javadoc 이 그
 * 사실을 이미 적고 있고, 반환값은 응답으로 나가고 사라진다.
 *
 * <p>트랜잭션 경계 자체는 여기서 재지 않는다(목이라 쿼리가 돌지 않는다). 그 축은
 * {@code ChatKickRecorderTest} 가 실제 Postgres 에서 잰다 — 같은 모양의 recorder 셋 중 하나에서
 * "감사가 조치와 함께 롤백된다" 가 측정되면 나머지는 같은 처방이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatBanReleaseRecorder — 지워진 행의 내용이 감사에만 남는다")
class ChatBanReleaseRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");

    @Mock
    private ChatBanStateRepository banStateRepository;
    @Mock
    private ChatAuditLogRepository auditLogRepository;

    private final UUID targetUserId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private ChatBanReleaseRecorder recorder() {
        return new ChatBanReleaseRecorder(banStateRepository, auditLogRepository);
    }

    private ChatAuditEntry appended() {
        ArgumentCaptor<ChatAuditEntry> captor = ArgumentCaptor.forClass(ChatAuditEntry.class);
        Mockito.verify(auditLogRepository).append(captor.capture());
        return captor.getValue();
    }

    /**
     * ⭐ 영구 밴을 푼 기록이 <b>영구였다는 사실</b>을 싣는다.
     *
     * <p>이 구분이 중요한 이유: 영구 밴은 사람이 넣은 것이고 그것을 되돌리는 앱 경로가 0건이다
     * (밴 생성 호출처가 {@code ChatKickRecorder} 하나이고 그 티어에 영구가 없다). 한 번 지우면
     * 어떤 앱 경로로도 다시 걸 수 없으므로, 무엇을 지웠는지가 남아야 한다.
     */
    @Test
    @DisplayName("⭐ 영구 밴을 풀면 permanent 로 남는다 — 되돌릴 경로가 없는 조치다")
    void releasingAPermanentBanRecordsThatItWasPermanent() {
        // given: 만료가 null = 영구
        UUID bannedById = UUID.randomUUID();
        given(banStateRepository.release(targetUserId, actorId, NOW))
                .willReturn(new Released(true, null, bannedById));

        // when
        recorder().release(targetUserId, actorId, ChatRole.ADMIN, NOW);

        // then
        ChatAuditEntry entry = appended();
        assertThat(entry.action()).isEqualTo(ChatAuditAction.CHAT_RELEASE_BAN);
        assertThat(entry.actorId()).isEqualTo(actorId);
        assertThat(entry.actorRole()).isEqualTo(ChatRole.ADMIN);
        assertThat(entry.targetId()).isEqualTo(targetUserId);
        assertThat(entry.occurredAt())
                .as("감사 시각이 사면 기준선과 갈렸다 — 둘은 같은 호출의 같은 값이어야 한다")
                .isEqualTo(NOW);
        assertThat(entry.detail())
                .containsEntry("banExisted", "true")
                .containsEntry("expiresAt", "permanent")
                .containsEntry("bannedById", bannedById.toString());
    }

    /** 기한부였으면 그 만료가 적힌다 — "언제까지였는지" 도 행과 함께 사라지는 값이다. */
    @Test
    @DisplayName("기한부 밴은 만료 시각이 남는다")
    void releasingATimedBanRecordsItsExpiry() {
        Instant expiresAt = NOW.plusSeconds(604_800);
        given(banStateRepository.release(targetUserId, actorId, NOW))
                .willReturn(new Released(true, expiresAt, UUID.randomUUID()));

        recorder().release(targetUserId, actorId, ChatRole.ADMIN, NOW);

        assertThat(appended().detail()).containsEntry("expiresAt", expiresAt.toString());
    }

    /**
     * ⭐ 풀 밴이 <b>없었어도</b> 감사 행이 남는다 — 조치가 없었던 것이 아니다. 그 호출만으로
     * 730일 누적이 접히고, 그 테이블은 append-only 라 앱 경로로 기준선을 낮출 수 없다.
     *
     * <p>그때 {@code expiresAt}·{@code bannedById} 는 넣지 않는다. 실을 값이 애초에 {@code null}
     * 이고, 빈칸을 "모름" 으로 적으면 영구 밴({@code expiresAt} 이 없는 경우)과 같은 모양이 된다.
     */
    @Test
    @DisplayName("⭐ 풀 밴이 없었어도 남긴다 — 그 호출만으로 누적이 접힌다")
    void aReleaseWithNoBanIsStillAudited() {
        given(banStateRepository.release(targetUserId, actorId, NOW))
                .willReturn(new Released(false, null, null));

        recorder().release(targetUserId, actorId, ChatRole.ADMIN, NOW);

        assertThat(appended().detail())
                .containsExactlyEntriesOf(java.util.Map.of("banExisted", "false"));
    }

    /**
     * ⭐ 순서가 <b>지운 다음 적는다</b>. 반대로 하면 무엇을 지웠는지 모르는 채로 적어야 하고,
     * 그 값은 이 호출 뒤 어디서도 읽을 수 없다.
     */
    @Test
    @DisplayName("⭐ 잠금 → 해제 → 감사 순서다 — 지우기 전에는 무엇을 지웠는지 모른다")
    void theAuditIsWrittenAfterTheDelete() {
        given(banStateRepository.release(targetUserId, actorId, NOW))
                .willReturn(new Released(true, null, UUID.randomUUID()));

        recorder().release(targetUserId, actorId, ChatRole.ADMIN, NOW);

        InOrder order = Mockito.inOrder(banStateRepository, auditLogRepository);
        order.verify(banStateRepository).lockForBanDecision(targetUserId);
        order.verify(banStateRepository).release(targetUserId, actorId, NOW);
        order.verify(auditLogRepository).append(Mockito.any());
    }
}
