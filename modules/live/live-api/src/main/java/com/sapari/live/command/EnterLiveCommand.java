package com.sapari.live.command;

import java.util.UUID;

/**
 * 라이브 입장 요청. 시청은 공개라 미인증(게스트)도 입장하지만, 채팅 룸 토큰 발급을 위해 인증 회원은
 * 신원(userId·role·nickname·email)을 함께 싣는다.
 *
 * <p>{@code userId == null}이면 미인증(게스트) — 이 경우 회원 룸 토큰은 발급하지 않는다
 * (게스트 에페메랄 토큰은 별도 처리). {@code role}은 api-app 값(USER/SELLER).
 */
public record EnterLiveCommand(
        UUID roomId,
        UUID userId,
        String role,
        String nickname,
        String email
) {
    public boolean isAuthenticated() {
        return userId != null;
    }
}
