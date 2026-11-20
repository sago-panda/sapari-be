package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

/**
 * 사전 한 줄 쓰기가 남기는 <b>감사 행의 내용</b>을 고정한다.
 *
 * <p>이 두 행위만 {@code detail} 을 싣는 이유가 여기서 보인다. 사전의 식별자는 단어 문자열이고,
 * <b>제거는 정본 행을 지운다</b> — 그 행 UUID 를 남겨도 가리킬 대상이 없으므로 감사가 답해야 하는
 * "무엇을 지웠나" 에 답을 못 한다. 그래서 {@code target_id} 를 비우고 항목을 평문으로 싣는다.
 *
 * <p>로그 쪽이 항목 대신 길이만 찍는 것과 어긋나 보이지만 근거가 다르다 — 그쪽은 민감도가 아니라
 * 한 줄의 분량이다(상한 없이 전량이 걸렸을 때 35,966자). 감사 행은 한 조치가 한 줄이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatFilterWordWriteRecorder — 지워진 단어는 감사에만 남는다")
class ChatFilterWordWriteRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");

    @Mock
    private ChatFilterWordRepository repository;
    @Mock
    private ChatAuditLogRepository auditLogRepository;

    private final UUID actorId = UUID.randomUUID();

    private ChatFilterWordWriteRecorder recorder() {
        return new ChatFilterWordWriteRecorder(repository, auditLogRepository);
    }

    private ChatAuditEntry appended() {
        ArgumentCaptor<ChatAuditEntry> captor = ArgumentCaptor.forClass(ChatAuditEntry.class);
        Mockito.verify(auditLogRepository).append(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("⭐ 추가가 항목을 평문으로 남긴다 — 대상 id 는 없다")
    void addingAWordRecordsIt() {
        // given
        given(repository.add(FilterWordKind.PROFANITY, "시발", actorId)).willReturn(true);

        // when
        boolean changed = recorder().add(FilterWordKind.PROFANITY, "시발", actorId, ChatRole.ADMIN, NOW);

        // then
        assertThat(changed).isTrue();
        ChatAuditEntry entry = appended();
        assertThat(entry.action()).isEqualTo(ChatAuditAction.CHAT_ADD_FILTER_WORD);
        assertThat(entry.actorId()).isEqualTo(actorId);
        assertThat(entry.actorRole()).isEqualTo(ChatRole.ADMIN);
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(entry.targetId())
                .as("단어는 UUID 로 가리켜지지 않는다 — 제거되면 그 UUID 가 아무것도 가리키지 않는다")
                .isNull();
        assertThat(entry.detail()).containsExactlyInAnyOrderEntriesOf(
                Map.of("kind", "PROFANITY", "word", "시발", "changed", "true"));
    }

    /**
     * ⭐ 제거의 감사가 <b>유일한 흔적</b>이다. 정본 행이 사라지므로 {@code created_by}·
     * {@code created_at} 도 함께 사라진다 — 추가 쪽은 그 둘이 행에 남아 있다.
     */
    @Test
    @DisplayName("⭐ 제거가 무엇을 지웠는지 남긴다 — 정본 행이 사라지므로 이 행이 전부다")
    void removingAWordRecordsWhatWasRemoved() {
        // given
        given(repository.remove(FilterWordKind.WHITELIST, "시발점")).willReturn(true);

        // when
        recorder().remove(FilterWordKind.WHITELIST, "시발점", actorId, ChatRole.ADMIN, NOW);

        // then
        ChatAuditEntry entry = appended();
        assertThat(entry.action()).isEqualTo(ChatAuditAction.CHAT_REMOVE_FILTER_WORD);
        assertThat(entry.detail()).containsEntry("word", "시발점").containsEntry("kind", "WHITELIST");
    }

    /**
     * ⭐ 정본이 안 바뀌어도 남긴다. {@code changed=false} 는 추가에서 "이미 있었다", 제거에서
     * "없었다" 는 뜻이고 둘 다 오류가 아니다 — 그 칸이 없으면 "감사에는 제거가 있는데 단어가 그대로"
     * 가 기록 누락처럼 읽힌다.
     */
    @Test
    @DisplayName("⭐ 정본이 안 바뀌어도 남긴다 — changed 가 그 사실을 적는다")
    void aNoOpWriteIsStillAudited() {
        given(repository.remove(FilterWordKind.PROFANITY, "없는단어")).willReturn(false);

        recorder().remove(FilterWordKind.PROFANITY, "없는단어", actorId, ChatRole.ADMIN, NOW);

        assertThat(appended().detail()).containsEntry("changed", "false");
    }
}
