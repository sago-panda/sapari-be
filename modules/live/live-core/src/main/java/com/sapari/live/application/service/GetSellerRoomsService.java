package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.common.page.PageSupport;
import com.sapari.live.command.GetSellerRoomsCommand;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetSellerRoomsUseCase;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.SellerLiveRoomCountsView;
import com.sapari.live.view.SellerLiveRoomSummary;

/** 판매자 내 방 목록(예정 시각 내림차순 키셋)과 탭별 개수. */
@Service
@RequiredArgsConstructor
public class GetSellerRoomsService implements GetSellerRoomsUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public CursorPage<SellerLiveRoomSummary> getRooms(GetSellerRoomsCommand command) {
        int size = PageSupport.normalizeSize(command.size());
        Cursor cursor = (command.cursor() == null) ? null : CursorCodec.decode(command.cursor());

        List<LiveRoom> rows = liveRoomRepository.findSellerRooms(
                command.sellerId(),
                LiveRoomTabs.statusesOf(command.tab()),
                cursor == null ? null : fromSortKey(cursor.sortKeyAsLong()),
                cursor == null ? null : cursor.idAsUuid(),
                size + 1);

        return CursorPage.of(rows.stream().map(GetSellerRoomsService::toSummary).toList(), size,
                v -> CursorCodec.encode(toSortKey(v.scheduledAt()), v.roomId().toString()));
    }

    // 커서의 정렬키는 epoch 마이크로초다 — scheduled_at(timestamptz)의 정밀도와 같아야 한다. 밀리초로 자르면
    // 경계 행의 소수 초가 사라져 "같은 시각·더 작은 id" 와 "같은 ms 안의 더 이른 시각" 행이 다음 페이지에서 빠진다.
    // 범위는 0001~9999년으로 막는다 — 변조된 큰 값이 DB 바인딩에서 500 이 되지 않고 400(InvalidCursor)이 되게.
    private static final Instant MIN_SORT_KEY = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_SORT_KEY = Instant.parse("9999-12-31T23:59:59.999999Z");

    private static String toSortKey(Instant scheduledAt) {
        return String.valueOf(scheduledAt.getEpochSecond() * 1_000_000L + scheduledAt.getNano() / 1_000);
    }

    private static Instant fromSortKey(long micros) {
        Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
        if (at.isBefore(MIN_SORT_KEY) || at.isAfter(MAX_SORT_KEY)) {
            throw new InvalidCursorException("커서 sortKey 범위 밖: " + micros);
        }
        return at;
    }

    @Override
    @Transactional(readOnly = true)
    public SellerLiveRoomCountsView getCounts(UUID sellerId) {
        Map<LiveRoomTab, Long> byTab = new EnumMap<>(LiveRoomTab.class);
        for (Map.Entry<Class<? extends LiveStatus>, Long> e : liveRoomRepository.countSellerRoomsByStatus(sellerId).entrySet()) {
            byTab.merge(LiveRoomTabs.tabOf(e.getKey()), e.getValue(), Long::sum);
        }
        return new SellerLiveRoomCountsView(
                byTab.getOrDefault(LiveRoomTab.SCHEDULED, 0L),
                byTab.getOrDefault(LiveRoomTab.LIVE, 0L),
                byTab.getOrDefault(LiveRoomTab.ENDED, 0L),
                byTab.getOrDefault(LiveRoomTab.CANCELLED, 0L));
    }

    private static SellerLiveRoomSummary toSummary(LiveRoom room) {
        return new SellerLiveRoomSummary(room.id(), room.title(), room.thumbnailUrl(),
                LiveRoomTabs.of(room.status()), room.scheduledAt(),
                LiveRoomTabs.startedAtOf(room.status()), LiveRoomTabs.endedAtOf(room.status()));
    }
}
