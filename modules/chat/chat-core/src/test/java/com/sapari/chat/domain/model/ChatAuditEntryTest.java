package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 감사 한 줄의 불변식. <b>조회가 틀린 행을 조용히 빼 간다</b>는 것이 이 단언들의 이유다 —
 * 그 테이블의 인덱스는 {@code (actor_id, actor_type, created_at)} 과 {@code (action, created_at)}
 * 둘뿐이라, 대상 칸이 어긋난 행은 전수 조회로만 드러난다.
 */
@DisplayName("ChatAuditEntry — 대상 칸은 행위가 정한다")
class ChatAuditEntryTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");

    private final UUID actorId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    private ChatAuditEntry entry(ChatAuditAction action, UUID target, Map<String, String> detail) {
        return new ChatAuditEntry(actorId, ChatRole.ADMIN, action, target, detail, NOW);
    }

    /**
     * 대상을 UUID 로 가리키는 행위 / 그렇지 않은 행위. <b>손으로 적은 목록이다</b> — 그래서 아래
     * {@code theTwoListsCoverEveryAction} 이 이 둘의 합집합을 {@code values()} 와 대조한다.
     *
     * <p>왜 {@code requiresTargetId()} 로 분기하지 않는가: 그러면 테스트가 재려는 그 짝을 피검사
     * 코드에서 읽어 오게 되어, 어느 행위의 짝이 뒤집혀도 테스트가 따라 뒤집힌다. 짝은 손으로
     * 박고, <b>목록이 전수인지</b>만 구조로 닫는다.
     */
    private static final EnumSet<ChatAuditAction> NEEDS_TARGET = EnumSet.of(
            ChatAuditAction.CHAT_KICK_USER,
            ChatAuditAction.CHAT_RELEASE_BAN,
            ChatAuditAction.CHAT_RECONCILE_KICK_LIST,
            ChatAuditAction.CHAT_CLEAR_ROOM_ENDED_MARKER);

    private static final EnumSet<ChatAuditAction> SPEAKS_THROUGH_DETAIL = EnumSet.of(
            ChatAuditAction.CHAT_ADD_FILTER_WORD,
            ChatAuditAction.CHAT_REMOVE_FILTER_WORD,
            ChatAuditAction.CHAT_RECONCILE_BAN_MIRROR);

    static Stream<ChatAuditAction> needsTarget() {
        return NEEDS_TARGET.stream();
    }

    static Stream<ChatAuditAction> speaksThroughDetail() {
        return SPEAKS_THROUGH_DETAIL.stream();
    }

    /**
     * ⭐ <b>이 가드의 분모가 대상의 전수와 같은지</b>를 박는다.
     *
     * <p>위 두 목록은 손열거이고 오늘 4 + 3 = 7 이 전량이다. 여덟 번째 행위를 더하면 어느 목록에도
     * 안 들어가 <b>조용히 미검증</b>이 되는데, 그 사실을 말해 주는 것이 아무것도 없었다. 지키는
     * 대상이 {@code values()} 이므로 분모도 거기에 맞춘다 — 새 행위는 이 칸에서 먼저 걸린다.
     */
    @Test
    @DisplayName("⭐ 두 목록이 행위 전수를 덮는다 — 새 행위가 조용히 빠지지 않는다")
    void theTwoListsCoverEveryAction() {
        EnumSet<ChatAuditAction> union = EnumSet.copyOf(NEEDS_TARGET);
        union.addAll(SPEAKS_THROUGH_DETAIL);

        assertThat(union)
                .as("새 행위가 두 목록 어디에도 없다 — 그 행위의 대상 칸 계약이 재어지지 않는다")
                .isEqualTo(EnumSet.allOf(ChatAuditAction.class));
        assertThat(NEEDS_TARGET)
                .as("한 행위가 두 목록에 다 있다 — 짝이 모순이다")
                .doesNotContainAnyElementsOf(SPEAKS_THROUGH_DETAIL);
    }

    /**
     * ⭐ 대상을 요구하는 행위에 {@code targetId} 가 없으면 그 행은 "누가 무언가를 했다" 까지만
     * 말한다. 강퇴·밴 해제·방 조치가 전부 이쪽이다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("needsTarget")
    @DisplayName("⭐ 대상을 요구하는 행위는 targetId 없이 만들어지지 않는다")
    void anActionThatNeedsATargetRejectsNull(ChatAuditAction action) {
        assertThat(action.requiresTargetId())
                .as("이 목록에 있는데 행위는 대상을 요구하지 않는다 — 짝이 갈렸다")
                .isTrue();
        assertThatThrownBy(() -> entry(action, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetId");
    }

    /**
     * ⭐ 반대 방향도 막는다. 사전 쓰기는 식별자가 단어 문자열이고, 제거는 정본 행을 지우므로 그 행
     * UUID 를 실어도 <b>가리킬 대상이 없다</b> — 그런데 조회는 그것을 대상으로 읽는다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("speaksThroughDetail")
    @DisplayName("⭐ 대상을 요구하지 않는 행위에 UUID 를 실으면 거부한다")
    void anActionWithoutATargetRejectsOne(ChatAuditAction action) {
        assertThat(action.requiresTargetId())
                .as("이 목록에 있는데 행위가 대상을 요구한다 — 짝이 갈렸다")
                .isFalse();
        assertThatThrownBy(() -> entry(action, targetId, Map.of("word", "시발")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetId");
    }

    /**
     * ⭐ 빈 맵은 {@code null} 로 접힌다. 접지 않으면 그 칸에 {@code {}} 가 들어가는데, 그건
     * "실을 것이 없다" 와 "직렬화가 비었다" 를 같은 모양으로 만든다 — 조회에서 {@code IS NULL} 로
     * 가르던 행위 구분이 사라진다.
     */
    @Test
    @DisplayName("⭐ 빈 detail 은 null 로 접힌다 — {} 가 들어가면 '없음' 과 구분되지 않는다")
    void anEmptyDetailFoldsToNull() {
        assertThat(entry(ChatAuditAction.CHAT_RELEASE_BAN, targetId, Map.of()).detail()).isNull();
        assertThat(entry(ChatAuditAction.CHAT_RELEASE_BAN, targetId, null).detail()).isNull();
    }

    /** 넘긴 맵을 나중에 고쳐도 기록은 안 바뀐다 — 기록은 그 시점의 사실이다. */
    @Test
    @DisplayName("detail 을 복사한다 — 호출자가 맵을 고쳐도 기록이 따라 바뀌지 않는다")
    void theDetailIsCopied() {
        Map<String, String> mutable = new HashMap<>(Map.of("banExisted", "true"));
        ChatAuditEntry made = entry(ChatAuditAction.CHAT_RELEASE_BAN, targetId, mutable);

        mutable.put("banExisted", "false");

        assertThat(made.detail()).containsExactlyEntriesOf(Map.of("banExisted", "true"));
    }

    /** 대상 <b>종류</b>는 행위가 들고 있다 — 호출자가 따로 넘기면 갈릴 수 있고, 갈린 행은 조회에서 빠진다. */
    @Test
    @DisplayName("targetType 은 행위에서 나온다 — 따로 받지 않는다")
    void theTargetTypeComesFromTheAction() {
        assertThat(entry(ChatAuditAction.CHAT_KICK_USER, targetId, null).targetType())
                .isEqualTo(ChatAuditTargetType.USER);
        assertThat(entry(ChatAuditAction.CHAT_ADD_FILTER_WORD, null, Map.of("word", "ㅅ")).targetType())
                .isEqualTo(ChatAuditTargetType.FILTER_WORD);
        assertThat(entry(ChatAuditAction.CHAT_RECONCILE_KICK_LIST, targetId, null).targetType())
                .isEqualTo(ChatAuditTargetType.LIVE_ROOM);
    }

    @Test
    @DisplayName("행위자·역할·시각은 필수다 — 누가 언제가 빠진 감사 행은 기록이 아니다")
    void theActorAndTheClockAreRequired() {
        assertThatThrownBy(() -> new ChatAuditEntry(null, ChatRole.ADMIN,
                ChatAuditAction.CHAT_KICK_USER, targetId, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatAuditEntry(actorId, null,
                ChatAuditAction.CHAT_KICK_USER, targetId, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatAuditEntry(actorId, ChatRole.ADMIN,
                ChatAuditAction.CHAT_KICK_USER, targetId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
