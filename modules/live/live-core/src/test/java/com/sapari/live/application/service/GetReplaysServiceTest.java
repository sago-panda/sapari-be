package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
import com.sapari.live.command.GetReplaysQuery;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.ReplaySummaryView;

@DisplayName("GetReplaysService — 공개 다시보기 목록")
class GetReplaysServiceTest {

    private final LiveRoomRepository repository = mock(LiveRoomRepository.class);
    private final GetReplaysService service = new GetReplaysService(repository);

    private static final Instant START = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    @DisplayName("첫 페이지는 size+1 건을 묻고, 항목에 제목·썸네일·길이·종료 시각을 담는다")
    void firstPage() {
        Instant ended = Instant.parse("2026-11-12T09:00:01.123456Z");
        LiveRoom a = room(new LiveStatus.Ended(START, ended, "https://cdn/a/playlist.m3u8"));
        LiveRoom b = room(new LiveStatus.Ended(START, ended.minusSeconds(60), "https://cdn/b/playlist.m3u8"));
        LiveRoom c = room(new LiveStatus.Ended(START, ended.minusSeconds(120), "https://cdn/c/playlist.m3u8"));
        given(repository.findReplays(null, null, 3)).willReturn(List.of(a, b, c));

        CursorPage<ReplaySummaryView> page = service.getReplays(new GetReplaysQuery(null, 2));

        assertThat(page.content()).extracting(ReplaySummaryView::roomId).containsExactly(a.id(), b.id());
        ReplaySummaryView first = page.content().getFirst();
        assertThat(first).isEqualTo(new ReplaySummaryView(a.id(), a.sellerId(), "판매자", "지난 방송",
                "https://cdn/thumbnails/x", 3601L, ended));
        assertThat(page.hasNext()).isTrue();
        var cursor = CursorCodec.decode(page.nextCursor());
        assertThat(GetUpcomingLivesService.fromMicros(cursor.sortKeyAsLong())).isEqualTo(ended.minusSeconds(60));
        assertThat(cursor.idAsUuid()).isEqualTo(b.id());
    }

    @Test
    @DisplayName("다음 페이지는 커서의 (종료 시각, id) 로 — 마이크로초까지 보존한다")
    void nextPage() {
        Instant at = Instant.parse("2026-11-12T09:00:00.654321Z");
        UUID id = UUID.randomUUID();
        String cursor = CursorCodec.encode(String.valueOf(GetUpcomingLivesService.toMicros(at)), id.toString());
        given(repository.findReplays(at, id, PageSupport.DEFAULT_SIZE + 1)).willReturn(List.of());

        CursorPage<ReplaySummaryView> page = service.getReplays(new GetReplaysQuery(cursor, null));

        assertThat(page.content()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("깨진 커서·범위 밖 커서는 400 계열 — 저장소를 부르지 않는다")
    void badCursor() {
        String outOfRange = CursorCodec.encode(String.valueOf(Long.MAX_VALUE), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.getReplays(new GetReplaysQuery("not-a-cursor", null)))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> service.getReplays(new GetReplaysQuery(outOfRange, null)))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("시작 시각이 없는 종료 방은 길이 null")
    void durationNullWithoutStart() {
        LiveRoom r = room(new LiveStatus.Ended(null, START, "https://cdn/a/playlist.m3u8"));
        given(repository.findReplays(null, null, PageSupport.DEFAULT_SIZE + 1)).willReturn(List.of(r));

        assertThat(service.getReplays(new GetReplaysQuery(null, null)).content().getFirst().durationSeconds()).isNull();
    }

    @Test
    @DisplayName("종료가 아닌 방이 섞이면 저장소 결함 — 조용히 넘기지 않는다")
    void nonEndedIsADefect() {
        given(repository.findReplays(null, null, PageSupport.DEFAULT_SIZE + 1))
                .willReturn(List.of(room(new LiveStatus.Scheduled(START))));

        assertThatThrownBy(() -> service.getReplays(new GetReplaysQuery(null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static LiveRoom room(LiveStatus status) {
        return LiveRoom.builder()
                .id(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .title("지난 방송")
                .sellerNickname("판매자")
                .thumbnailUrl("https://cdn/thumbnails/x")
                .streamType(new LiveStreamType.WebRtc())
                .vodPublic(true)
                .vodStatus(VodStatus.READY)
                .status(status)
                .build();
    }
}
