package com.sapari.chat.domain.rule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;

@DisplayName("ChatPermissionPolicy")
class ChatPermissionPolicyTest {

    private final ChatPermissionPolicy policy = new ChatPermissionPolicy();

    @DisplayName("canSend — 권한 매트릭스(반환 boolean)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("canSendCases")
    void canSend(String tc, ChatRole role, boolean isRoomOwner, ChatMessageType type, boolean expected) {
        // when
        boolean result = policy.canSend(role, isRoomOwner, type);

        // then
        assertThat(result).isEqualTo(expected);
    }

    static Stream<Arguments> canSendCases() {
        return Stream.of(
                arguments("#1 BUYER·NORMAL → 허용", ChatRole.BUYER, false, new ChatMessageType.Normal(), true),
                arguments("#2 BUYER·NOTICE → 거부", ChatRole.BUYER, false, new ChatMessageType.Notice(), false),
                arguments("#3 BUYER·SYSTEM → 거부", ChatRole.BUYER, false, new ChatMessageType.System("ENTER"), false),
                arguments("#4a SELLER(소유)·NORMAL → 허용(소유 무관)", ChatRole.SELLER, true, new ChatMessageType.Normal(), true),
                arguments("#4b SELLER(비소유)·NORMAL → 허용(소유 무관)", ChatRole.SELLER, false, new ChatMessageType.Normal(), true),
                arguments("#5 SELLER(소유)·NOTICE → 허용", ChatRole.SELLER, true, new ChatMessageType.Notice(), true),
                arguments("#5a SELLER(비소유, 남의 방)·NOTICE → 거부", ChatRole.SELLER, false, new ChatMessageType.Notice(), false),
                arguments("#6 SELLER·SYSTEM → 거부", ChatRole.SELLER, true, new ChatMessageType.System("ENTER"), false),
                arguments("#7 ADMIN·NORMAL → 허용", ChatRole.ADMIN, false, new ChatMessageType.Normal(), true),
                arguments("#8 ADMIN·NOTICE → 허용", ChatRole.ADMIN, false, new ChatMessageType.Notice(), true),
                arguments("#9 ADMIN·SYSTEM → 거부", ChatRole.ADMIN, false, new ChatMessageType.System("ENTER"), false),
                arguments("#10 GUEST·NORMAL → 거부", ChatRole.GUEST, false, new ChatMessageType.Normal(), false),
                arguments("#11 GUEST·NOTICE → 거부", ChatRole.GUEST, false, new ChatMessageType.Notice(), false),
                arguments("#12 GUEST·SYSTEM → 거부", ChatRole.GUEST, false, new ChatMessageType.System("ENTER"), false),
                arguments("#12a GUEST·위조 isRoomOwner=true·NOTICE → 거부(방어)", ChatRole.GUEST, true, new ChatMessageType.Notice(), false)
        );
    }

    @DisplayName("canKick — 강퇴 권한(반환 boolean)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("canKickCases")
    void canKick(String tc, ChatRole kickerRole, UUID kickerId, UUID roomOwnerId,
                 ChatRole targetRole, UUID targetUserId, boolean expected) {
        // when
        boolean result = policy.canKick(kickerRole, kickerId, roomOwnerId, targetRole, targetUserId);

        // then
        assertThat(result).isEqualTo(expected);
    }

    static Stream<Arguments> canKickCases() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        UUID admin = UUID.fromString("00000000-0000-0000-0000-0000000000d4"); // ADMIN ≠ 방주인을 분명히
        return Stream.of(
                arguments("#13 SELLER(방주인) → BUYER 강퇴 → 허용", ChatRole.SELLER, owner, owner, ChatRole.BUYER, target, true),
                arguments("#14 SELLER(방주인) → 방문 SELLER 강퇴 → 허용", ChatRole.SELLER, owner, owner, ChatRole.SELLER, target, true),
                arguments("#15 SELLER(방주인) → ADMIN → 거부", ChatRole.SELLER, owner, owner, ChatRole.ADMIN, target, false),
                arguments("#16 SELLER(남의 방) → BUYER → 거부", ChatRole.SELLER, other, owner, ChatRole.BUYER, target, false),
                arguments("#17 ADMIN → BUYER → 허용", ChatRole.ADMIN, other, owner, ChatRole.BUYER, target, true),
                arguments("#18 ADMIN → SELLER → 허용", ChatRole.ADMIN, other, owner, ChatRole.SELLER, target, true),
                arguments("#19 ADMIN → ADMIN → 거부", ChatRole.ADMIN, other, owner, ChatRole.ADMIN, target, false),
                arguments("#20 BUYER 강퇴 시도 → 거부", ChatRole.BUYER, other, owner, ChatRole.BUYER, target, false),
                arguments("#21 GUEST 강퇴 시도 → 거부", ChatRole.GUEST, other, owner, ChatRole.BUYER, target, false),
                arguments("#22 SELLER self-kick → 거부", ChatRole.SELLER, owner, owner, ChatRole.SELLER, owner, false),
                arguments("#23 ADMIN self-kick → 거부", ChatRole.ADMIN, admin, owner, ChatRole.ADMIN, admin, false)
        );
    }

    @Nested
    @DisplayName("노출 수준과 감사 — 갈리면 안 되는 두 판정")
    class Visibility {

        /**
         * ⭐ <b>특권 뷰와 감사가 같은 함수에서 나온다.</b>
         *
         * <p>전에는 팬아웃({@code canModerate})과 감사가 각자 {@code isRoomOwner || role == ADMIN}을
         * 적고 있었다. 그러면 특권 뷰를 받는 역할이 하나 늘 때 한쪽만 따라가는데, 뒤처지는 쪽이 감사다 —
         * <b>원문과 이메일은 받는데 흔적은 안 남는</b> 방향이라 조용하고 나쁜 쪽으로 갈린다.
         *
         * <p>모든 역할 × 소유 조합을 훑어 두 판정이 같은 전제에서 나오는지 본다. 새 역할이 추가되면
         * 이 테스트가 그 역할까지 자동으로 검사한다.
         */
        @ParameterizedTest(name = "{0} / 방주인={1}")
        @MethodSource("com.sapari.chat.domain.rule.ChatPermissionPolicyTest#everyRoleAndOwnership")
        @DisplayName("⭐ 원문을 받는 조합과 감사에 남는 조합을 표로 못 박는다")
        void visibilityAndAuditMatchTheTable(ChatRole role, boolean isRoomOwner) {
            // given: 기대값을 정책 식에서 유도하지 않는다. 유도하면 좌우변이 같은 식이 되어, 정책이
            // 전원에게 원문을 열어도 통과한다(실제로 그렇게 썼다가 잡혔다). 표를 따로 두면 역할이 늘 때
            // 이 표를 고치는 것이 곧 "감사까지 함께 봤다"는 증거가 된다.
            boolean expectedFull = isRoomOwner || ROLES_THAT_READ_THE_ORIGINAL.contains(role);

            // when & then
            assertThat(policy.seesUnmaskedContent(role, isRoomOwner))
                    .as("원문·이메일을 받는 조합이 표와 다르다")
                    .isEqualTo(expectedFull);
            assertThat(policy.usesPrivilegedViewInSomeoneElsesRoom(role, isRoomOwner))
                    .as("특권 뷰를 받는데 감사에 안 남는다 — 노출만 늘고 흔적은 준다")
                    .isEqualTo(expectedFull && !isRoomOwner);
        }

        @Test
        @DisplayName("기본은 마스킹이다 — 뒤집으면 새 역할이 늘 때마다 빠뜨림으로 노출된다")
        void maskedIsTheDefault() {
            // when & then
            assertThat(policy.seesUnmaskedContent(ChatRole.BUYER, false)).isFalse();
            assertThat(policy.seesUnmaskedContent(ChatRole.GUEST, false)).isFalse();
            // 남의 방에 온 판매자는 시청자다 — 소유 기반 게이팅을 도입한 이유가 이것이다
            assertThat(policy.seesUnmaskedContent(ChatRole.SELLER, false)).isFalse();
        }

        @Test
        @DisplayName("방 주인과 관리자는 원문을 본다 — 무엇이 오갔는지 못 보면 무엇을 끊을지 판단할 수 없다")
        void ownersAndAdminsSeeTheOriginal() {
            // when & then
            assertThat(policy.seesUnmaskedContent(ChatRole.SELLER, true)).isTrue();
            assertThat(policy.seesUnmaskedContent(ChatRole.ADMIN, false)).isTrue();
        }

        @Test
        @DisplayName("자기 방을 보는 것은 특권 사용이 아니다 — 감사에 남기지 않는다")
        void ownRoomIsNotPrivilegedUse() {
            // when & then
            assertThat(policy.usesPrivilegedViewInSomeoneElsesRoom(ChatRole.SELLER, true)).isFalse();
        }
    }

    /**
     * 원문과 발신자 이메일을 받는 역할. <b>정책 코드와 따로</b> 적는다 — 정책에서 유도하면 무엇을 바꿔도
     * 통과하는 항진식이 된다. 역할을 늘리는 사람이 이 표를 함께 고쳐야 하고, 그 수정이 곧 감사까지
     * 검토했다는 증거다. (방 주인은 역할과 무관하게 받으므로 여기 없다.)
     */
    private static final java.util.Set<ChatRole> ROLES_THAT_READ_THE_ORIGINAL =
            java.util.EnumSet.of(ChatRole.ADMIN);

    /**
     * 모든 역할 × 소유 조합. 역할이 늘면 여기도 자동으로 늘어난다.
     *
     * <p>불가능한 조합은 <b>세션 불변식에게 물어서</b> 뺀다. 조건을 손으로 적으면({@code role == SELLER})
     * 그 불변식이 완화되는 날 필터가 조용히 낡아, 새로 가능해진 조합이 스윕에서 영영 빠진다 —
     * 그리고 두 파일이 다른 모듈이라 아무도 안 깨진다.
     */
    static Stream<Arguments> everyRoleAndOwnership() {
        return Stream.of(ChatRole.values())
                .flatMap(role -> Stream.of(true, false)
                        .filter(owner -> isRepresentableAsASession(role, owner))
                        .map(owner -> Arguments.of(role, owner)));
    }

    private static boolean isRepresentableAsASession(ChatRole role, boolean isRoomOwner) {
        try {
            new ChatSession(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    role, "닉네임", null, isRoomOwner);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
