package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.common.page.PageSupport;
import com.sapari.live.command.GetUpcomingLivesQuery;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetUpcomingLivesUseCase;
import com.sapari.live.view.UpcomingLiveView;

/**
 * 예정 방송 목록. 커서 정렬키는 {@code scheduledAt} 의 <b>epoch 마이크로초</b>다 — Postgres {@code timestamptz} 정밀도와
 * 같다. 밀리초로 자르면 같은 밀리초 안의 두 방에서 keyset 비교가 어긋나 행이 빠지거나 겹친다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetUpcomingLivesService implements GetUpcomingLivesUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    public CursorPage<UpcomingLiveView> getUpcoming(GetUpcomingLivesQuery query) {
        int size = PageSupport.normalizeSize(query.size());
        Cursor cursor = query.cursor() == null ? null : CursorCodec.decode(query.cursor());
        Instant afterScheduledAt = cursor == null ? null : fromMicros(cursor.sortKeyAsLong());
        List<UpcomingLiveView> rows = liveRoomRepository
                .findUpcoming(afterScheduledAt, cursor == null ? null : cursor.idAsUuid(), size + 1)
                .stream().map(GetUpcomingLivesService::toView)
                .toList();
        return CursorPage.of(rows, size,
                v -> CursorCodec.encode(String.valueOf(toMicros(v.scheduledAt())), v.roomId().toString()));
    }

    /**
     * 예정 시각은 상태에서 읽는다 — 저장소에서 읽은 방은 최상위 {@code scheduledAt} 이 비어 있다({@code LiveRoomMapper}).
     * 저장소가 예약·준비만 돌려주므로 다른 상태는 호출 경로의 결함이다.
     */
    private static UpcomingLiveView toView(LiveRoom room) {
        Instant scheduledAt = switch (room.status()) {
            case LiveStatus.Scheduled s -> s.scheduledAt();
            case LiveStatus.Ready r -> r.scheduledAt();
            default -> throw new IllegalStateException(
                    "예정 목록에 시작 전이 아닌 방이 섞였다. roomId=" + room.id()
                            + ", status=" + room.status().getClass().getSimpleName());
        };
        return new UpcomingLiveView(room.id(), room.sellerId(), room.sellerNickname(), room.title(),
                room.thumbnailUrl(), scheduledAt);
    }

    static long toMicros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    /**
     * 커서는 신뢰할 수 없는 입력이다 — DB 가 담을 수 없는 시각(조작한 극단값)은 바인딩에서 500 이 되므로 여기서 400 으로 막는다.
     * 범위는 예정 시각으로 의미 있는 0000~9999년이다.
     */
    static Instant fromMicros(long micros) {
        if (micros < MIN_CURSOR_MICROS || micros > MAX_CURSOR_MICROS) {
            throw new InvalidCursorException("커서 정렬키가 시각 범위를 벗어났다");
        }
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    private static final long MIN_CURSOR_MICROS = toMicros(Instant.parse("0000-01-01T00:00:00Z"));
    private static final long MAX_CURSOR_MICROS = toMicros(Instant.parse("9999-12-31T23:59:59.999999Z"));
}
