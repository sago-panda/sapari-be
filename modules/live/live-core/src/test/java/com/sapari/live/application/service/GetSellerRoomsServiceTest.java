package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.live.command.GetSellerRoomsCommand;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.SellerLiveRoomCountsView;
import com.sapari.live.view.SellerLiveRoomSummary;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 내 방 목록 — 키셋 조립과 탭별 개수")
class GetSellerRoomsServiceTest {

    @Mock private LiveRoomRepository liveRoomRepository;
    @InjectMocks private GetSellerRoomsService service;

    private final UUID sellerId = UUID.randomUUID();

    private LiveRoom room(Instant scheduledAt, LiveStatus status) {
        return LiveRoom.builder().id(UUID.randomUUID()).sellerId(sellerId).title("제목").sellerNickname("닉")
                .status(status).scheduledAt(scheduledAt).build();
    }

    @Test
    @DisplayName("첫 페이지 — size+1 로 조회하고, 넘치면 마지막 항목의 (scheduledAt, id) 가 다음 커서")
    void firstPage() {
        Instant base = Instant.parse("2026-06-10T10:00:00Z");
        List<LiveRoom> rows = IntStream.range(0, 3)
                .mapToObj(i -> room(base.minusSeconds(i * 60L), new LiveStatus.Scheduled(base.minusSeconds(i * 60L))))
                .toList();
        given(liveRoomRepository.findSellerRooms(eq(sellerId), eq(LiveRoomTabs.statusesOf(LiveRoomTab.SCHEDULED)),
                isNull(), isNull(), eq(3))).willReturn(rows);

        CursorPage<SellerLiveRoomSummary> page = service.getRooms(
                new GetSellerRoomsCommand(sellerId, LiveRoomTab.SCHEDULED, null, 2));

        assertThat(page.content()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        Cursor next = CursorCodec.decode(page.nextCursor());
        assertThat(next.sortKeyAsLong()).isEqualTo(rows.get(1).scheduledAt().getEpochSecond() * 1_000_000L);
        assertThat(next.idAsUuid()).isEqualTo(rows.get(1).id());
        assertThat(page.content().get(0).status()).isEqualTo(LiveRoomTab.SCHEDULED);
    }

    @Test
    @DisplayName("커서가 있으면 그 (scheduledAt, id) 다음부터, 탭 없으면 전체 상태")
    void nextPage() {
        Instant at = Instant.parse("2026-06-10T10:00:00Z");
        UUID lastId = UUID.randomUUID();
        String cursor = CursorCodec.encode(String.valueOf(at.getEpochSecond() * 1_000_000L), lastId.toString());
        given(liveRoomRepository.findSellerRooms(sellerId, LiveRoomTabs.ALL, at, lastId, 16)).willReturn(List.of());

        CursorPage<SellerLiveRoomSummary> page = service.getRooms(new GetSellerRoomsCommand(sellerId, null, cursor, null));

        assertThat(page.content()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("잘못된 커서 — InvalidCursorException(400), 조회하지 않는다")
    void invalidCursor() {
        assertThatThrownBy(() -> service.getRooms(new GetSellerRoomsCommand(sellerId, null, "%%%", null)))
                .isInstanceOf(InvalidCursorException.class);
        then(liveRoomRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("크기는 정규화된다 — 상한을 넘겨도 100+1 로 조회")
    void sizeIsNormalized() {
        given(liveRoomRepository.findSellerRooms(any(), any(), any(), any(), anyInt())).willReturn(List.of());

        service.getRooms(new GetSellerRoomsCommand(sellerId, null, null, 10_000));

        then(liveRoomRepository).should().findSellerRooms(sellerId, LiveRoomTabs.ALL, null, null, 101);
    }

    @Test
    @DisplayName("탭별 개수 — 상태를 탭으로 합산, 없는 탭은 0")
    void counts() {
        Map<Class<? extends LiveStatus>, Long> byStatus = Map.of(
                LiveStatus.Scheduled.class, 2L,
                LiveStatus.Ready.class, 1L,
                LiveStatus.Suspended.class, 4L,
                LiveStatus.Cancelled.class, 5L);
        given(liveRoomRepository.countSellerRoomsByStatus(sellerId)).willReturn(byStatus);

        assertThat(service.getCounts(sellerId)).isEqualTo(new SellerLiveRoomCountsView(3, 4, 0, 5));
    }

    @Test
    @DisplayName("요약의 시작·종료 시각은 상태에서, 예정 시각은 모든 상태에 있다")
    void summaryTimes() {
        Instant at = Instant.parse("2026-06-10T10:00:00Z");
        LiveRoom ended = room(at, new LiveStatus.Ended(at.plusSeconds(10), at.plusSeconds(100), null));
        given(liveRoomRepository.findSellerRooms(any(), any(), any(), any(), anyInt())).willReturn(List.of(ended));

        SellerLiveRoomSummary s = service.getRooms(new GetSellerRoomsCommand(sellerId, null, null, null)).content().get(0);

        assertThat(s).isEqualTo(new SellerLiveRoomSummary(ended.id(), "제목", null, LiveRoomTab.ENDED,
                at, at.plusSeconds(10), at.plusSeconds(100)));
        assertThat(Set.of(s.status())).containsExactly(LiveRoomTab.ENDED);
    }

    @Test
    @DisplayName("커서는 마이크로초까지 보존한다 — 밀리초로 자르면 같은 ms 안의 경계 행이 빠진다")
    void cursorKeepsMicros() {
        Instant at = Instant.parse("2026-06-10T10:00:00.123456Z");
        List<LiveRoom> rows = List.of(room(at, new LiveStatus.Scheduled(at)), room(at, new LiveStatus.Scheduled(at)));
        given(liveRoomRepository.findSellerRooms(any(), any(), any(), any(), anyInt())).willReturn(rows);

        String next = service.getRooms(new GetSellerRoomsCommand(sellerId, null, null, 1)).nextCursor();
        service.getRooms(new GetSellerRoomsCommand(sellerId, null, next, 1));

        then(liveRoomRepository).should().findSellerRooms(sellerId, LiveRoomTabs.ALL, at, rows.get(0).id(), 2);
    }

    @Test
    @DisplayName("범위 밖 커서 — DB 바인딩까지 가지 않고 400")
    void outOfRangeCursor() {
        String cursor = CursorCodec.encode(String.valueOf(Long.MAX_VALUE), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.getRooms(new GetSellerRoomsCommand(sellerId, null, cursor, null)))
                .isInstanceOf(InvalidCursorException.class);
        then(liveRoomRepository).shouldHaveNoInteractions();
    }
}
