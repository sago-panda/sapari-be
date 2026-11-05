package com.sapari.chat.command;

import java.util.UUID;

/**
 * 강퇴 명단 재적재 명령 (live-app REST).
 *
 * <p>본문에서 오는 값이 없다. {@code roomId}는 경로에서, {@code actorId}·{@code actorRole}은
 * <b>인증된 주체</b>에서 온다 — 강퇴·해제와 같은 이유로 역할을 실을 자리를 만들지 않는다.
 *
 * <p><b>명단을 받지 않는 것이 이 계약의 핵심이다.</b> 재적재는 정본({@code chat_kick_log})에서
 * 읽어 채운다. 호출자가 명단을 실어 보내면 그걸로 <b>정본에 없는 사람을 집행 명단에 올릴 수</b>
 * 있고, 그러면 이 엔드포인트가 복구 수단이 아니라 감사 없는 강퇴 수단이 된다.
 */
public record ReconcileKickListCommand(
        UUID roomId,
        UUID actorId,
        String actorRole
) {
}
