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
}
