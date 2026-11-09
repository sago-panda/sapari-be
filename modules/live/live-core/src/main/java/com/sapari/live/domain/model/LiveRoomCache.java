package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.Instant;
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
        long currentViewers,
        String title,
        Instant startedAt
) {
    /**
     * 방송 중인 방으로 목록 항목을 만든다. 시청자 수는 0 — 채우는 것은 재조정({@code RefreshLiveListService})이다.
     *
     * <p>Live 가 아닌 방을 넘기면 던진다. 목록에 넣는 호출은 Live 전이 직후에만 있으므로, 여기 걸리면
     * 호출 경로의 결함이다 — 조용히 건너뛰면 목록에서 방송이 사라진 이유를 찾을 수 없다.
     */
    public static LiveRoomCache fromLive(LiveRoom room) {
        if (!(room.status() instanceof LiveStatus.Live live)) {
            throw new IllegalStateException("방송 중이 아닌 방은 목록에 넣을 수 없다. roomId=" + room.id()
                    + ", status=" + room.status().getClass().getSimpleName());
        }
        return LiveRoomCache.builder()
                .roomId(room.id())
                .sellerId(room.sellerId())
                .sellerNickname(room.sellerNickname())
                .thumbnailUrl(room.thumbnailUrl())
                .hlsUrl(live.hlsUrl())
                .title(room.title())
                .startedAt(live.startedAt())
                .currentViewers(0L)
                .build();
    }

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
                .title(title)
                .startedAt(startedAt)
                .build();
    }
}
