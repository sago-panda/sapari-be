package com.sapari.chat.domain.service;

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
}
