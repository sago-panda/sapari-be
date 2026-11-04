package com.sapari.chat.domain.model;

import java.util.UUID;

public record ChatSession(
        UUID roomId,
        UUID userId,
        ChatRole role,
        String nickname,        // GUEST는 null
        String email,           // GUEST·비노출 대상은 null
        boolean isRoomOwner     // 방 주인(SELLER)인지 — 입장 시 room:live 값(sellerId)==userId로 도출. NOTICE·이메일 노출 게이트
) {
    public ChatSession {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (role == null) {
            throw new IllegalArgumentException("role은 필수입니다.");
        }
        // 방 소유는 방 단위 권한이고 소유자는 반드시 SELLER — BUYER·GUEST·ADMIN이 owner인 모순 인스턴스를 구조적으로 차단
        if (isRoomOwner && role != ChatRole.SELLER) {
            throw new IllegalArgumentException("isRoomOwner는 role=SELLER만 가능합니다.");
        }
    }
}
