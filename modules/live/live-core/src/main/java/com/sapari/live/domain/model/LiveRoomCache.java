package com.sapari.live.domain.model;

import lombok.Builder;

import java.util.UUID;

import com.sapari.live.view.GetLiveView.LiveRoomSummary;

/**
 * Redis에 캐싱되는 라이브 방송 정보.
 * 메인 페이지 라이브 목록 조회 시 DB 없이 Redis만으로 응답하기 위해 사용.
 * pinnedProductName/pinnedProductImageUrl은 product 모듈 도입 시 채워짐 (현재는 null).
 */
@Builder
public record LiveRoomCache(
        UUID roomId,
        UUID sellerId,
        String sellerNickname,
        String thumbnailUrl,
        String hlsUrl,
        String pinnedProductName,
        String pinnedProductImageUrl,
        long currentViewers
) {
    public LiveRoomSummary toSummary(){
        return LiveRoomSummary.builder()
                .roomId(roomId)
                .sellerId(sellerId)
                .sellerNickname(sellerNickname)
                .thumbnailUrl(thumbnailUrl)
                .hlsUrl(hlsUrl)
                .pinnedProductName(pinnedProductName)
                .pinnedProductImageUrl(pinnedProductImageUrl)
                .currentViewers(currentViewers)
                .build();
    }
}
