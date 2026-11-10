package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.GetRecentLivesQuery;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.RecentView;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.domain.repository.RecentViewRepository;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.RecentLiveView;

@DisplayName("최근 본 라이브 목록 — 90일·기록 순서·커서·없는 방")
class GetRecentLivesServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private final RecentViewRepository recentViewRepository = mock(RecentViewRepository.class);
    private final LiveRoomRepository liveRoomRepository = mock(LiveRoomRepository.class);
    private final TimeProvider timeProvider = mock(TimeProvider.class);
    private final GetRecentLivesService service =
            new GetRecentLivesService(recentViewRepository, liveRoomRepository, timeProvider);

    private final UUID userId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(timeProvider.now()).willReturn(NOW);
    }

    private LiveRoom scheduled(UUID id) {
        return LiveRoom.create(sellerId, "제목-" + id, "설명", "판매자", "https://cdn/t.jpg", T0.plusSeconds(3600), T0)
                .toBuilder().id(id).build();
    }

    private LiveRoom live(UUID id) {
        return scheduled(id).startLive(StreamInfo.of("sfu", "egress", "https://cdn/master.m3u8", null), T0.plusSeconds(3600));
    }

    private LiveRoom ended(UUID id) {
        return live(id).endLive(T0.plusSeconds(7200));
    }

    private RecentView view(UUID roomId, Instant at) {
        return new RecentView(userId, roomId, at);
    }

    @Test
    @DisplayName("90일 전부터, size+1 개로 묻는다")
    void queriesNinetyDaysWithSizePlusOne() {
        given(recentViewRepository.findRecent(any(), any(), any(), any(), anyInt())).willReturn(List.of());

        service.getRecent(new GetRecentLivesQuery(userId, null, 10));

        verify(recentViewRepository).findRecent(userId, NOW.minus(Duration.ofDays(90)), null, null, 11);
    }

    @Test
    @DisplayName("기록 순서를 지킨다 — 방 일괄 조회가 순서를 섞어도")
    void keepsRecordOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        given(recentViewRepository.findRecent(any(), any(), any(), any(), anyInt()))
                .willReturn(List.of(view(a, NOW.minusSeconds(1)), view(b, NOW.minusSeconds(2)), view(c, NOW.minusSeconds(3))));
        given(liveRoomRepository.findAllByIds(Set.of(a, b, c))).willReturn(List.of(ended(c), live(a), ended(b)));

        CursorPage<RecentLiveView> page = service.getRecent(new GetRecentLivesQuery(userId, null, 10));

        assertThat(page.content()).extracting(RecentLiveView::roomId).containsExactly(a, b, c);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("필드 — 상태는 판매자 탭 묶음, 시작 시각은 상태에서, 시청 시각은 기록에서")
    void mapsFields() {
        UUID liveId = UUID.randomUUID();
        UUID endedId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        Instant viewedAt = NOW.minus(5, ChronoUnit.MICROS);
        given(recentViewRepository.findRecent(any(), any(), any(), any(), anyInt())).willReturn(List.of(
                view(liveId, viewedAt), view(endedId, viewedAt.minusSeconds(1)), view(cancelledId, viewedAt.minusSeconds(2))));
        given(liveRoomRepository.findAllByIds(any())).willReturn(List.of(
                live(liveId), ended(endedId), scheduled(cancelledId).cancel(T0.plusSeconds(60))));

        List<RecentLiveView> content = service.getRecent(new GetRecentLivesQuery(userId, null, 10)).content();

        assertThat(content.get(0)).isEqualTo(new RecentLiveView(liveId, sellerId, "판매자", "제목-" + liveId,
                "https://cdn/t.jpg", LiveRoomTab.LIVE, T0.plusSeconds(3600), viewedAt));
        assertThat(content.get(1).status()).isEqualTo(LiveRoomTab.ENDED);
        assertThat(content.get(1).startedAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(content.get(2).status()).isEqualTo(LiveRoomTab.CANCELLED);
        assertThat(content.get(2).startedAt()).isNull();
    }

    @Test
    @DisplayName("가리키는 방이 없으면 그 항목만 빠지고, 다음 페이지 판정·커서는 기록 기준이다")
    void missingRoomSkippedButPagingFollowsRecords() {
        UUID a = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID extra = UUID.randomUUID();
        Instant goneAt = NOW.minusSeconds(2);
        given(recentViewRepository.findRecent(any(), any(), any(), any(), anyInt()))
                .willReturn(List.of(view(a, NOW.minusSeconds(1)), view(gone, goneAt), view(extra, NOW.minusSeconds(3))));
        given(liveRoomRepository.findAllByIds(Set.of(a, gone))).willReturn(List.of(live(a)));

        CursorPage<RecentLiveView> page = service.getRecent(new GetRecentLivesQuery(userId, null, 2));

        assertThat(page.content()).extracting(RecentLiveView::roomId).containsExactly(a);
        assertThat(page.hasNext()).isTrue();
        Cursor next = CursorCodec.decode(page.nextCursor());
        assertThat(next.idAsUuid()).isEqualTo(gone);
        assertThat(next.sortKeyAsLong()).isEqualTo(ChronoUnit.MICROS.between(Instant.EPOCH, goneAt));
    }

    @Test
    @DisplayName("커서는 µs 정렬키 + roomId — 다음 호출에서 그대로 풀려 키셋 인자가 된다")
    void cursorRoundTrip() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant aAt = Instant.parse("2026-11-09T09:00:00.123456Z");
        given(recentViewRepository.findRecent(eq(userId), any(), eq(null), eq(null), anyInt()))
                .willReturn(List.of(view(a, aAt), view(b, aAt.minusSeconds(1))));
        given(liveRoomRepository.findAllByIds(any())).willReturn(List.of(live(a)));

        String cursor = service.getRecent(new GetRecentLivesQuery(userId, null, 1)).nextCursor();
        given(recentViewRepository.findRecent(eq(userId), any(), eq(aAt), eq(a), anyInt())).willReturn(List.of());
        service.getRecent(new GetRecentLivesQuery(userId, cursor, 1));

        verify(recentViewRepository).findRecent(userId, NOW.minus(Duration.ofDays(90)), aAt, a, 2);
    }

    @Test
    @DisplayName("깨진 커서·범위 밖 정렬키는 InvalidCursorException(400)")
    void invalidCursor() {
        assertThatThrownBy(() -> service.getRecent(new GetRecentLivesQuery(userId, "not-a-cursor", 10)))
                .isInstanceOf(InvalidCursorException.class);
        String outOfRange = CursorCodec.encode(String.valueOf(Long.MAX_VALUE), UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.getRecent(new GetRecentLivesQuery(userId, outOfRange, 10)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("기록이 없으면 빈 페이지이고 방을 읽지 않는다")
    void emptyHistory() {
        given(recentViewRepository.findRecent(any(), any(), any(), any(), anyInt())).willReturn(List.of());

        CursorPage<RecentLiveView> page = service.getRecent(new GetRecentLivesQuery(userId, null, null));

        assertThat(page.content()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        verify(liveRoomRepository, never()).findAllByIds(any());
    }
}
