package com.sapari.streamingapp.websocket.auth;

import java.util.UUID;

/**
 * 핸드셰이크에서 JWT로 검증한 채팅 연결의 신원.
 *
 * <p>이 연결이 살아있는 동안 보내는 모든 메시지는 클라이언트가 주장하는 값이 아니라
 * 이 신원을 신뢰원으로 쓴다(senderId·role 위조 방지 = 신뢰 경계의 출발점).
 *
 * <p>isRoomOwner·roomId는 "인증" 결과가 아니라 "입장 게이트(room:live 값 == userId)"에서 도출되므로
 * 여기 두지 않는다 — 그 둘은 연결 세션 상태로 따로 들고 간다.
 */
public record ChatPrincipal(
        UUID userId,
        String role,        // BUYER | SELLER | ADMIN | GUEST — JWT role claim 원본(권한 판정은 도메인이 ChatRole로 변환)
        String nickname,    // 비회원(GUEST)·미발급 시 null
        String email        // 비회원(GUEST)·미발급 시 null
) {
}
