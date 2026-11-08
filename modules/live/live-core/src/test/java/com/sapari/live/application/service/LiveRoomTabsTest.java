package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.view.LiveRoomTab;

@DisplayName("상태 ↔ 탭 — 두 표현이 어긋나지 않는다")
class LiveRoomTabsTest {

    private static final Instant T = Instant.parse("2026-06-10T10:00:00Z");

    private static final List<LiveStatus> ONE_OF_EACH = List.of(
            new LiveStatus.Scheduled(T),
            new LiveStatus.Ready(T),
            new LiveStatus.Live(T, "sfu", "eg", "https://hls"),
            new LiveStatus.Suspended(T, T, "사유"),
            new LiveStatus.Ended(T, T, null),
            new LiveStatus.Cancelled(T, T));

    @Test
    @DisplayName("표본이 sealed permits 전부다")
    void sampleCoversAllPermits() {
        assertThat(ONE_OF_EACH.stream().map(Object::getClass).toArray())
                .containsExactlyInAnyOrder((Object[]) LiveStatus.class.getPermittedSubclasses());
        assertThat(LiveRoomTabs.ALL.toArray())
                .containsExactlyInAnyOrder((Object[]) LiveStatus.class.getPermittedSubclasses());
    }

    @Test
    @DisplayName("Ready 는 예정, Suspended 는 방송 중으로 묶인다")
    void grouping() {
        assertThat(ONE_OF_EACH.stream().map(LiveRoomTabs::of)).containsExactly(
                LiveRoomTab.SCHEDULED, LiveRoomTab.SCHEDULED, LiveRoomTab.LIVE, LiveRoomTab.LIVE,
                LiveRoomTab.ENDED, LiveRoomTab.CANCELLED);
    }

    @Test
    @DisplayName("of(상태) 와 statusesOf(탭)·tabOf(종류)가 같은 답을 낸다")
    void bothDirectionsAgree() {
        for (LiveStatus status : ONE_OF_EACH) {
            LiveRoomTab tab = LiveRoomTabs.of(status);
            assertThat(LiveRoomTabs.statusesOf(tab)).contains(status.getClass());
            assertThat(LiveRoomTabs.tabOf(status.getClass())).isEqualTo(tab);
        }
        assertThat(LiveRoomTabs.statusesOf(null)).isEqualTo(LiveRoomTabs.ALL);
    }

    @Test
    @DisplayName("endedAt 은 Ended 만 — 취소 시각은 내보내지 않는다")
    void endedAtOnlyForEnded() {
        assertThat(LiveRoomTabs.endedAtOf(new LiveStatus.Ended(T, T.plusSeconds(1), null))).isEqualTo(T.plusSeconds(1));
        assertThat(LiveRoomTabs.endedAtOf(new LiveStatus.Cancelled(T, T.plusSeconds(1)))).isNull();
        assertThat(LiveRoomTabs.startedAtOf(new LiveStatus.Cancelled(T, T))).isNull();
    }
}
