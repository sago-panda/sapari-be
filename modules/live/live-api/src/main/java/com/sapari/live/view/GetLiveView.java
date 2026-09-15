package com.sapari.live.view;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

public record GetLiveView(
        List<LiveRoomSummary> rooms
) {
    @Builder
    public record LiveRoomSummary(
            UUID roomId,
            UUID sellerId,
            String sellerNickname,
            String thumbnailUrl,
            String hlsUrl,
            String pinnedProductName,
            String pinnedProductImageUrl,
            long currentViewers
    ) {}
}
