package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.PageSupport;
import com.sapari.live.command.GetReplaysQuery;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetReplaysUseCase;
import com.sapari.live.view.ReplaySummaryView;

/**
 * 다시보기 공개 목록. 노출 조건은 저장소 쿼리가 거른다. 커서 정렬키는 {@code endedAt} 의 epoch 마이크로초
 * ({@link GetUpcomingLivesService#toMicros} 와 같은 이유 — 컬럼 정밀도).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetReplaysService implements GetReplaysUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    public CursorPage<ReplaySummaryView> getReplays(GetReplaysQuery query) {
        int size = PageSupport.normalizeSize(query.size());
        Cursor cursor = query.cursor() == null ? null : CursorCodec.decode(query.cursor());
        Instant afterEndedAt = cursor == null ? null : GetUpcomingLivesService.fromMicros(cursor.sortKeyAsLong());
        List<ReplaySummaryView> rows = liveRoomRepository
                .findReplays(afterEndedAt, cursor == null ? null : cursor.idAsUuid(), size + 1)
                .stream().map(GetReplaysService::toView)
                .toList();
        return CursorPage.of(rows, size,
                v -> CursorCodec.encode(String.valueOf(GetUpcomingLivesService.toMicros(v.endedAt())),
                        v.roomId().toString()));
    }

    /** 저장소가 종료 방만 돌려주므로 다른 상태는 호출 경로의 결함이다. */
    private static ReplaySummaryView toView(LiveRoom room) {
        if (!(room.status() instanceof LiveStatus.Ended ended)) {
            throw new IllegalStateException("다시보기 목록에 종료가 아닌 방이 섞였다. roomId=" + room.id()
                    + ", status=" + room.status().getClass().getSimpleName());
        }
        return new ReplaySummaryView(room.id(), room.sellerId(), room.sellerNickname(), room.title(),
                room.thumbnailUrl(), GetLiveReplayService.durationSeconds(ended), ended.endedAt());
    }
}
