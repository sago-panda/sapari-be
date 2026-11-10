package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.PageSupport;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.GetRecentLivesQuery;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.RecentView;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.domain.repository.RecentViewRepository;
import com.sapari.live.port.GetRecentLivesUseCase;
import com.sapari.live.view.RecentLiveView;

/**
 * 최근 본 라이브. 기록을 키셋으로 한 페이지 읽고, 그 방들을 한 번에 읽어 기록 순서대로 붙인다.
 * 커서 정렬키는 {@code viewedAt} 의 <b>epoch 마이크로초</b>다 — 이유는 {@link GetUpcomingLivesService} 와 같다.
 *
 * <p><b>페이지 판정·커서는 기록 기준이다.</b> 가리키는 방이 없는 기록(방 삭제 기능이 없어 생기지 않아야 한다)은 화면에서만
 * 빠진다 — 뷰 목록으로 {@code hasNext} 를 정하면 그만큼 짧아진 페이지가 끝으로 오판된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetRecentLivesService implements GetRecentLivesUseCase {

    /** 노출 기간(SPR-172 결정, 고정값). 더 오래된 행은 지우지 않고 목록에서만 뺀다. */
    static final Duration VISIBLE_PERIOD = Duration.ofDays(90);

    private final RecentViewRepository recentViewRepository;
    private final LiveRoomRepository liveRoomRepository;
    private final TimeProvider timeProvider;

    @Override
    public CursorPage<RecentLiveView> getRecent(GetRecentLivesQuery query) {
        int size = PageSupport.normalizeSize(query.size());
        Cursor cursor = query.cursor() == null ? null : CursorCodec.decode(query.cursor());
        Instant since = timeProvider.now().minus(VISIBLE_PERIOD);
        List<RecentView> rows = recentViewRepository.findRecent(query.userId(), since,
                cursor == null ? null : GetUpcomingLivesService.fromMicros(cursor.sortKeyAsLong()),
                cursor == null ? null : cursor.idAsUuid(),
                size + 1);
        CursorPage<RecentView> records = CursorPage.of(rows, size,
                v -> CursorCodec.encode(String.valueOf(GetUpcomingLivesService.toMicros(v.viewedAt())),
                        v.roomId().toString()));
        if (records.content().isEmpty()) {
            return new CursorPage<>(List.of(), records.nextCursor(), records.hasNext());
        }

        Map<UUID, LiveRoom> rooms = liveRoomRepository
                .findAllByIds(records.content().stream().map(RecentView::roomId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(LiveRoom::id, Function.identity()));
        List<RecentLiveView> views = records.content().stream()
                .map(v -> toView(v, rooms.get(v.roomId())))
                .filter(Objects::nonNull)
                .toList();
        return new CursorPage<>(views, records.nextCursor(), records.hasNext());
    }

    private static RecentLiveView toView(RecentView view, LiveRoom room) {
        if (room == null) {
            return null;
        }
        return new RecentLiveView(room.id(), room.sellerId(), room.sellerNickname(), room.title(), room.thumbnailUrl(),
                LiveRoomTabs.tabOf(room.status().getClass()), LiveRoomTabs.startedAtOf(room.status()), view.viewedAt());
    }
}
