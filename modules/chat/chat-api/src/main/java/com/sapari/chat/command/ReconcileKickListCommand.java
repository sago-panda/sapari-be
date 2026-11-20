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

    /**
     * 형제 {@code ReleaseBanCommand} 가 적어 둔 것과 같은 이유로 채운다 — 오늘 벡터는 없지만
     * (유일한 생성자가 컨트롤러이고 세 값이 전부 프레임워크에서 온다) <b>같은 불변식이 두 레이어에
     * 갈려 있었다</b>: {@code actorRole == null} 거부가 커맨드가 아니라 서비스에만 있었다.
     *
     * <p>이 경로에서 그 갈림이 한 칸 더 무겁다. 감사 기록이 {@code actorId}·{@code actorRole} 을
     * 싣는데, 이 둘은 <b>DB 트랜잭션이 없는 경로</b>라 그 기록을 조치 뒤에 만든다 — 커맨드가
     * 비어 있으면 거기서야 터지고, 그때는 조치가 이미 수행된 뒤다.
     *
     * <p>{@code actorRole} 의 <i>값</i>은 보지 않는다 — 모르는 이름의 판정은
     * {@code ChatRole.fromPlatformRole} 하나가 쥔다. 여기서 또 보면 역할 목록이 두 곳에 생긴다.
     */
    public ReconcileKickListCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole은 필수입니다.");
        }
    }
}
