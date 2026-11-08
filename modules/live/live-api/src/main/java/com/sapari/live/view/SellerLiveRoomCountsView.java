package com.sapari.live.view;

/** 판매자 내 방의 탭별 개수. 방이 없는 탭도 0 으로 나간다. */
public record SellerLiveRoomCountsView(
        long scheduled,
        long live,
        long ended,
        long cancelled
) {
}
