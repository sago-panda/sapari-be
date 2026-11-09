package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveRoomCacheTest {

    private static final Instant STARTED = Instant.parse("2026-11-06T05:00:00Z");

    @Test
    @DisplayName("방송 중인 방으로 목록 항목을 만들면 제목·시작 시각·재생 주소가 담기고 시청자 수는 0이다")
    void fromLive_copiesDisplayFields() {
        UUID sellerId = UUID.randomUUID();
        LiveRoom room = room(new LiveStatus.Live(STARTED, "sfu-1", "egress-1", "https://cdn/live/index.m3u8"))
                .toBuilder().sellerId(sellerId).build();

        LiveRoomCache cache = LiveRoomCache.fromLive(room);

        assertThat(cache.roomId()).isEqualTo(room.id());
        assertThat(cache.sellerId()).isEqualTo(sellerId);
        assertThat(cache.sellerNickname()).isEqualTo("판매자");
        assertThat(cache.thumbnailUrl()).isEqualTo("https://cdn/thumb.png");
        assertThat(cache.title()).isEqualTo("겨울 신상");
        assertThat(cache.startedAt()).isEqualTo(STARTED);
        assertThat(cache.hlsUrl()).isEqualTo("https://cdn/live/index.m3u8");
        assertThat(cache.currentViewers()).isZero();
        assertThat(cache.pinnedProductName()).isNull();
    }

    @Test
    @DisplayName("목록 요약에도 제목과 시작 시각이 실린다")
    void toSummary_carriesTitleAndStartedAt() {
        LiveRoomCache cache = LiveRoomCache.fromLive(
                room(new LiveStatus.Live(STARTED, "sfu-1", "egress-1", "https://cdn/live/index.m3u8")));

        var summary = cache.toSummary();

        assertThat(summary.title()).isEqualTo("겨울 신상");
        assertThat(summary.startedAt()).isEqualTo(STARTED);
    }

    @Test
    @DisplayName("방송 중이 아닌 방은 목록 항목이 될 수 없다 — 호출 경로의 결함을 드러낸다")
    void fromLive_rejectsNonLive() {
        assertThatThrownBy(() -> LiveRoomCache.fromLive(room(new LiveStatus.Scheduled(STARTED))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LiveRoomCache.fromLive(room(new LiveStatus.Ready(STARTED))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LiveRoomCache.fromLive(room(new LiveStatus.Ended(STARTED, STARTED, null))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static LiveRoom room(LiveStatus status) {
        return LiveRoom.builder()
                .id(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .title("겨울 신상")
                .sellerNickname("판매자")
                .thumbnailUrl("https://cdn/thumb.png")
                .streamType(new LiveStreamType.WebRtc())
                .status(status)
                .scheduledAt(STARTED)
                .build();
    }
}
