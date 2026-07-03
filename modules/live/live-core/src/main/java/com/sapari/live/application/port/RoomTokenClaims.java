package com.sapari.live.application.port;

import java.util.UUID;

/**
 * 룸 토큰({@link RoomTokenIssuer})에 담길 claim 값 묶음.
 *
 * <p>회원/게스트 공통 형태다. 게스트는 {@code userId}=에페메랄 UUID, {@code role}="GUEST",
 * {@code owner}=false, {@code nickname}/{@code email}=null. {@code owner}는 방주인(=SELLER 본인)
 * 여부로, chat의 PII·공지 게이트에 쓰인다.
 *
 * <p>{@code email}은 PII다. JWT는 서명만 되고 암호화되지 않아 payload가 base64로 열람 가능하므로,
 * ① 토큰 원문을 로그에 남기지 않고, ② 소비 측(chat) WS 핸드셰이크에서 <b>query param 전달을 금지</b>한다
 * (access log·브라우저 히스토리·Referer 잔류 방지 — 서브프로토콜/첫 프레임으로 전달). email 자체는
 * 방주인 PII 뷰의 소스라 토큰에 싣는다(per-message 유저서비스 조회 회피).
 */
public record RoomTokenClaims(
        UUID userId,
        UUID roomId,
        String role,
        boolean owner,
        String nickname,
        String email
) {
    /** email(PII)·nickname을 마스킹한다 — record 기본 toString()의 로그 유출 방지. */
    @Override
    public String toString() {
        return "RoomTokenClaims[userId=" + userId + ", roomId=" + roomId
                + ", role=" + role + ", owner=" + owner + ", nickname=***, email=***]";
    }
}
