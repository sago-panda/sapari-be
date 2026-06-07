package com.sapari.chat.command;

import java.util.UUID;

/**
 * 강퇴 명령 (api-app REST). 방 주인 여부는 서비스가 방을 로드해 roomOwnerId로 판정하므로
 * 커맨드는 강퇴자 신원만 담는다(세션 boolean을 신뢰하지 않음 — REST는 DB 로드 경로).
 */
public record KickUserCommand(
        UUID roomId,
        UUID kickerId,
        String kickerRole,
        UUID targetUserId
) {
    public KickUserCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (kickerId == null) {
            throw new IllegalArgumentException("kickerId는 필수입니다.");
        }
        if (kickerRole == null || kickerRole.isBlank()) {
            throw new IllegalArgumentException("kickerRole은 필수입니다.");
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId는 필수입니다.");
        }
    }
}
