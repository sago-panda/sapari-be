package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.common.page.PageSupport;
import com.sapari.live.command.GetUpcomingLivesQuery;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.UpcomingLiveView;

class GetUpcomingLivesServiceTest {

    private final LiveRoomRepository repository = mock(LiveRoomRepository.class);
    private final GetUpcomingLivesService service = new GetUpcomingLivesService(repository);

    @Test
    @DisplayName("첫 페이지는 커서 없이 size+1 건을 묻고, 넘치면 다음 커서를 만든다")
    void firstPageFetchesOneExtra() {
        Instant t1 = Instant.parse("2026-11-07T10:00:00.123456Z");
        LiveRoom a = room(new LiveStatus.Scheduled(t1));
        LiveRoom b = room(new LiveStatus.Ready(t1.plusSeconds(60)));
        LiveRoom c = room(new LiveStatus.Scheduled(t1.plusSeconds(120)));
        given(repository.findUpcoming(null, null, 3)).willReturn(List.of(a, b, c));

        CursorPage<UpcomingLiveView> page = service.getUpcoming(new GetUpcomingLivesQuery(null, 2));

        assertThat(page.content()).extracting(UpcomingLiveView::roomId).containsExactly(a.id(), b.id());
        assertThat(page.content().get(1).scheduledAt()).isEqualTo(t1.plusSeconds(60));
        assertThat(page.hasNext()).isTrue();
        var cursor = CursorCodec.decode(page.nextCursor());
        assertThat(GetUpcomingLivesService.fromMicros(cursor.sortKeyAsLong())).isEqualTo(t1.plusSeconds(60));
        assertThat(cursor.idAsUuid()).isEqualTo(b.id());
    }

    @Test
    @DisplayName("다음 페이지는 커서의 (예정 시각, id) 뒤부터 — 마이크로초까지 보존한다")
    void nextPageUsesCursorKeys() {
        Instant at = Instant.parse("2026-11-07T10:00:00.123456Z");
        UUID id = UUID.randomUUID();
        String cursor = CursorCodec.encode(String.valueOf(GetUpcomingLivesService.toMicros(at)), id.toString());
        given(repository.findUpcoming(at, id, PageSupport.DEFAULT_SIZE + 1)).willReturn(List.of());

        CursorPage<UpcomingLiveView> page = service.getUpcoming(new GetUpcomingLivesQuery(cursor, null));

        assertThat(page.content()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("크기는 상한으로 보정한다")
    void sizeIsCapped() {
        given(repository.findUpcoming(isNull(), isNull(), anyInt())).willReturn(List.of());

        service.getUpcoming(new GetUpcomingLivesQuery(null, 10_000));

        verify(repository).findUpcoming(null, null, PageSupport.MAX_SIZE + 1);
    }

    @Test
    @DisplayName("깨진 커서는 400 계열 예외 — 저장소를 부르지 않는다")
    void brokenCursorIsRejected() {
        assertThatThrownBy(() -> service.getUpcoming(new GetUpcomingLivesQuery("not-a-cursor", null)))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("시각 범위를 벗어난 조작 커서는 DB 까지 가지 않고 400 계열로 막는다")
    void outOfRangeCursorIsRejected() {
        String cursor = CursorCodec.encode(String.valueOf(Long.MIN_VALUE), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.getUpcoming(new GetUpcomingLivesQuery(cursor, null)))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(repository);
    }

    private static LiveRoom room(LiveStatus status) {
        return LiveRoom.builder()
                .id(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .title("예정 방송")
                .sellerNickname("판매자")
                .streamType(new LiveStreamType.WebRtc())
                .status(status)
                .build();
    }
}
