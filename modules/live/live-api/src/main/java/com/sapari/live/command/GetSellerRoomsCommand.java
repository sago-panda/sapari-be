package com.sapari.live.command;

import java.util.UUID;

import com.sapari.live.view.LiveRoomTab;

/**
 * @param tab    null 이면 전체
 * @param cursor null 이면 첫 페이지
 * @param size   null 이면 기본값(정규화는 서비스가 한다)
 */
public record GetSellerRoomsCommand(
        UUID sellerId,
        LiveRoomTab tab,
        String cursor,
        Integer size
) {
}
