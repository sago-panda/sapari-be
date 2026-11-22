package com.sapari.live.application.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.view.LiveRoomTab;

/**
 * 내부 상태 ↔ 판매자에게 보이는 탭. 목록 필터·탭별 개수·상세의 status 가 모두 이것을 쓴다.
 *
 * <p>Ready 는 예정에(아직 방송 전), Suspended 는 방송 중에(시작된 방) 묶는다.
 * {@link #of} 는 sealed switch 라 상태가 늘면 컴파일이 알려 주고, {@link #STATUSES} 는
 * {@code LiveRoomTabsTest} 가 두 표현이 어긋나지 않는지 대조한다.
 */
final class LiveRoomTabs {

    static final Map<LiveRoomTab, Set<Class<? extends LiveStatus>>> STATUSES = new EnumMap<>(Map.of(
            LiveRoomTab.SCHEDULED, Set.of(LiveStatus.Scheduled.class, LiveStatus.Ready.class),
            LiveRoomTab.LIVE, Set.of(LiveStatus.Live.class, LiveStatus.Suspended.class),
            LiveRoomTab.ENDED, Set.of(LiveStatus.Ended.class),
            LiveRoomTab.CANCELLED, Set.of(LiveStatus.Cancelled.class)));

    static final Set<Class<? extends LiveStatus>> ALL = Set.of(
            LiveStatus.Scheduled.class, LiveStatus.Ready.class, LiveStatus.Live.class,
            LiveStatus.Suspended.class, LiveStatus.Ended.class, LiveStatus.Cancelled.class);

    private LiveRoomTabs() {
    }

    static LiveRoomTab of(LiveStatus status) {
        return switch (status) {
            case LiveStatus.Scheduled s -> LiveRoomTab.SCHEDULED;
            case LiveStatus.Ready r -> LiveRoomTab.SCHEDULED;
            case LiveStatus.Live l -> LiveRoomTab.LIVE;
            case LiveStatus.Suspended s -> LiveRoomTab.LIVE;
            case LiveStatus.Ended e -> LiveRoomTab.ENDED;
            case LiveStatus.Cancelled c -> LiveRoomTab.CANCELLED;
        };
    }

    /** @param tab null 이면 전체 상태 */
    static Set<Class<? extends LiveStatus>> statusesOf(LiveRoomTab tab) {
        return tab == null ? ALL : STATUSES.get(tab);
    }

    static LiveRoomTab tabOf(Class<? extends LiveStatus> type) {
        return STATUSES.entrySet().stream()
                .filter(e -> e.getValue().contains(type))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("탭에 묶이지 않은 상태: " + type));
    }

    /** 상태 variant 에서 시작 시각. 방송이 시작된 적 없는 상태는 null. */
    static Instant startedAtOf(LiveStatus status) {
        return switch (status) {
            case LiveStatus.Scheduled s -> null;
            case LiveStatus.Ready r -> null;
            case LiveStatus.Live l -> l.startedAt();
            case LiveStatus.Suspended s -> s.startedAt();
            case LiveStatus.Ended e -> e.startedAt();
            case LiveStatus.Cancelled c -> null;
        };
    }

    /** 공개 뷰용 종료 시각. Ended 만 있다 — 취소 시각은 공개 뷰에 내보내지 않는다(판매자 뷰는 {@link #endedAtForSeller}). */
    static Instant endedAtOf(LiveStatus status) {
        return status instanceof LiveStatus.Ended e ? e.endedAt() : null;
    }

    /**
     * 판매자 화면(내 방 목록·상세)과 관리자 방 목록용 시작 시각. 관리자 종료한 방(방송 중 Cancelled)도 시작 시각을 보인다.
     * 공개 상세·최근 본 라이브는 {@link #startedAtOf} 를 쓴다 — 관리자 종료한 방의 시각은 판매자·관리자만 본다(SPR-211).
     */
    static Instant startedAtForSeller(LiveStatus status) {
        return status instanceof LiveStatus.Cancelled c ? c.startedAt() : startedAtOf(status);
    }

    /** 판매자 화면·관리자 방 목록용 종료 시각. 방송했던 취소 방(관리자 종료)은 끊긴 시각, 판매자 예약 취소 방은 없음. */
    static Instant endedAtForSeller(LiveStatus status) {
        if (status instanceof LiveStatus.Cancelled c) {
            return c.startedAt() != null ? c.cancelledAt() : null;
        }
        return endedAtOf(status);
    }
}
