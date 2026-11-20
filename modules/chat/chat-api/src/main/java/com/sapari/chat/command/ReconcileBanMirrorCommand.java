package com.sapari.chat.command;

import java.util.UUID;

/**
 * 밴 미러 재적재 명령 (live-app REST).
 *
 * <p>강퇴 명단 재적재({@link ReconcileKickListCommand})와 같은 이유로 <b>명단을 받지 않는다</b> —
 * 재적재는 정본({@code chat_ban})에서 읽어 채운다. 호출자가 명단을 실어 보내면 정본에 없는 사람을
 * 막을 수 있고, 그러면 이 엔드포인트가 감사 없는 밴 수단이 된다.
 */
public record ReconcileBanMirrorCommand(
        UUID actorId,
        String actorRole
) {

    public ReconcileBanMirrorCommand {
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole은 필수입니다.");
        }
    }
}
