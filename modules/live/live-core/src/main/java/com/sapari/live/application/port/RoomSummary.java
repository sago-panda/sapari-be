package com.sapari.live.application.port;

import java.time.Instant;

/**
 * LiveKit 에 살아 있는 SFU 방 1건(고아 정리 배치 전용).
 *
 * @param roomName     LiveKit 방 이름. 우리 규칙상 {@code roomId.toString()} 이고, {@code closeRoom} 이
 *                     받는 값과 같다(생성 시 {@code sfuRoomId} 로 저장하는 것도 이 값이다).
 * @param participants 현재 참가자 수. <b>판정에는 쓰지 않고 로그에만 싣는다</b> — DB 가 Ended 면 참가자가
 *                     0 이든 아니든 있어선 안 되는 방이다. 다만 0 인지 아닌지가 "종료 정리 실패로 남은
 *                     빈 방"과 "판매자가 토큰으로 되돌아온 방"을 사후에 가른다.
 * @param createdAt    방 생성 시각. 유예 계산용이며 알 수 없으면 {@code null}.
 */
public record RoomSummary(
        String roomName,
        int participants,
        Instant createdAt
) {
}
