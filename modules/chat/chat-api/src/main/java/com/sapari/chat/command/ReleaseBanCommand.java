package com.sapari.chat.command;

import java.util.UUID;

/**
 * 밴 해제 명령 (live-app REST).
 *
 * <p><b>요청 본문에서 오는 값이 없다.</b> {@code targetUserId}는 경로에서, {@code actorId}와
 * {@code actorRole}은 <b>인증된 주체</b>에서 온다. 강퇴 명령과 같은 이유다 — 역할을 실을 자리를
 * 만들면 구매자가 자기를 ADMIN이라 적어 남의 제재를 풀 수 있다.
 *
 * <p><b>방 id가 없다.</b> 밴은 방이 아니라 사람에 붙으므로({@code ChatBan}) 어느 방에서 눌렀는지가
 * 판정을 바꾸지 않는다. 그래서 엔드포인트도 방 경로 밖에 있다. 이 자리에 {@code roomId}를 두면
 * 다음 사람이 "자기 방이면 풀 수 있다" 가지를 더하게 되고, 그건 자동 승격이 요구한
 * <b>서로 다른 판매자 N명</b>의 독립성을 한 명이 되돌리는 길이 된다.
 *
 * <p>사유를 받지 않는다. 받으면 자유 텍스트가 감사 기록의 유일한 내용이 되는데, 그건 누른 사람이
 * 지어낼 수 있는 값이다 — 강퇴 증거를 {@code messageId}로 받기로 한 것과 같은 판단이다. 남기는 것은
 * <b>누가 언제</b>이고 그 둘은 서버가 채운다({@code chat_ban_release}).
 *
 * @param actorRole 인증 주체의 역할(플랫폼 역할 이름). <b>요청 본문에서 받지 말 것.</b>
 */
public record ReleaseBanCommand(
        UUID targetUserId,
        UUID actorId,
        String actorRole
) {
}
