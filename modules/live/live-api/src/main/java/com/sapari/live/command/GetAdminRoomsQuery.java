package com.sapari.live.command;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.view.LiveRoomTab;

/**
 * 관리자 전체 방 목록 요청. 필터는 모두 선택이고, null 이면 그 조건을 걸지 않는다.
 *
 * @param from 예정 시각 하한(포함)
 * @param to   예정 시각 상한(제외)
 * @param page 0부터. null 이면 0
 * @param size null 이면 기본값(정규화는 서비스가 한다)
 */
public record GetAdminRoomsQuery(
        LiveRoomTab status,
        UUID sellerId,
        Instant from,
        Instant to,
        Integer page,
        Integer size
) {
}
