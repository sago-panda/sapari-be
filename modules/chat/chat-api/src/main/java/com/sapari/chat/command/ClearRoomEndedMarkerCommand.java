package com.sapari.chat.command;

import java.util.UUID;

/**
 * 종료 마커 해제 명령 (live-app REST).
 *
 * <p>본문에서 오는 값이 없다 — {@code roomId}는 경로에서, 나머지는 인증된 주체에서 온다.
 *
 * <p><b>"정말 해제하겠다" 같은 확인 플래그를 두지 않는다.</b> 이 명령은 진위 검증을 통과해야만
 * 수행되고(그 방이 live 정본에서 실제로 진행 중일 때만) 그 검증이 플래그보다 강하다. 플래그를
 * 두면 호출자가 그것만 켜서 끝난 방의 마커도 지울 수 있게 되는데, 그건 이 마커가 존재하는 이유를
 * 지우는 일이다.
 */
public record ClearRoomEndedMarkerCommand(
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
    public ClearRoomEndedMarkerCommand {
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
