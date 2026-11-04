package com.sapari.chat.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 플랫폼 밴 — 방이 아니라 <b>사람</b>에 붙는다. 어느 방에도 들어가지 못한다.
 *
 * <p>강퇴({@link ChatKickLog})와 다른 테이블에 사는 이유는 수명이 달라서다. 강퇴는 일어난 사건이라 지우지
 * 않고, 밴은 풀 수 있어야 하는 상태다. 해제는 행 삭제고, 그래서 "해제됨" 같은 열이 없다.
 *
 * @param expiresAt 만료 시각. {@code null}이면 영구다 — 해제하려면 행을 지운다.
 */
public record ChatBan(
        UUID userId,
        UUID bannedById,
        Instant expiresAt,
        Instant createdAt
) {

    public ChatBan {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (bannedById == null) {
            throw new IllegalArgumentException("bannedById는 필수입니다.");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt은 필수입니다.");
        }
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                    "만료가 생성 시각보다 뒤여야 합니다 — 이미 끝난 밴은 만드는 순간 무의미합니다.");
        }
    }

    /** 누적 강퇴가 임계에 닿아 서버가 자동으로 거는 밴. 발급자는 사람이 아니라 시스템이다. */
    public static ChatBan escalated(UUID userId, ChatBanTier tier, Instant now) {
        return new ChatBan(userId, ChatConstants.SYSTEM_SENDER_ID, tier.expiresAt(now), now);
    }

    /** 영구 밴인가 — 만료를 두지 않았다는 뜻이다. */
    public boolean isPermanent() {
        return expiresAt == null;
    }
}
