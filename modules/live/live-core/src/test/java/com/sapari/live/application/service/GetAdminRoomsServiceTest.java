package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.common.page.OffsetPage;
import com.sapari.live.command.GetAdminRoomsQuery;
import com.sapari.live.domain.exception.InvalidLiveQueryException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.AdminRoomFilter;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.AdminLiveRoomSummary;
import com.sapari.live.view.LiveRoomTab;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 전체 방 목록 — 필터 조립·페이지 메타·입력 거부")
class GetAdminRoomsServiceTest {

    @Mock private LiveRoomRepository liveRoomRepository;
    @InjectMocks private GetAdminRoomsService service;

    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");
    private final UUID sellerId = UUID.randomUUID();

    private LiveRoom room(LiveStatus status) {
        return LiveRoom.builder().id(UUID.randomUUID()).sellerId(sellerId).title("제목").sellerNickname("닉")
                .status(status).scheduledAt(AT).build();
    }

    @Test
    @DisplayName("필터를 그대로 넘기고, 목록과 개수가 같은 조건을 쓴다 — 상태는 탭 묶음으로 펼친다")
    void passesFilter() {
        AdminRoomFilter expected = new AdminRoomFilter(
                LiveRoomTabs.statusesOf(LiveRoomTab.SCHEDULED), sellerId, AT, AT.plusSeconds(3600));
        given(liveRoomRepository.findAdminRooms(expected, 2, 10)).willReturn(List.of());
        given(liveRoomRepository.countAdminRooms(expected)).willReturn(25L);

        OffsetPage<AdminLiveRoomSummary> page = service.getRooms(
                new GetAdminRoomsQuery(LiveRoomTab.SCHEDULED, sellerId, AT, AT.plusSeconds(3600), 2, 10));

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25L);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("필터 없음 — 전체 상태·전체 판매자, page 는 0, size 는 기본값")
    void defaults() {
        AdminRoomFilter all = new AdminRoomFilter(LiveRoomTabs.ALL, null, null, null);
        given(liveRoomRepository.findAdminRooms(all, 0, 15)).willReturn(List.of());

        service.getRooms(new GetAdminRoomsQuery(null, null, null, null, null, null));

        then(liveRoomRepository).should().findAdminRooms(all, 0, 15);
        then(liveRoomRepository).should().countAdminRooms(all);
    }

    @Test
    @DisplayName("크기는 정규화된다 — 상한을 넘기면 100")
    void sizeIsNormalized() {
        given(liveRoomRepository.findAdminRooms(any(), anyInt(), anyInt())).willReturn(List.of());

        OffsetPage<AdminLiveRoomSummary> page =
                service.getRooms(new GetAdminRoomsQuery(null, null, null, null, 0, 10_000));

        assertThat(page.size()).isEqualTo(100);
        then(liveRoomRepository).should()
                .findAdminRooms(new AdminRoomFilter(LiveRoomTabs.ALL, null, null, null), 0, 100);
    }

    @Test
    @DisplayName("음수 page — LIVE-020, 조회하지 않는다")
    void negativePageRejected() {
        assertThatThrownBy(() -> service.getRooms(new GetAdminRoomsQuery(null, null, null, null, -1, null)))
                .isInstanceOfSatisfying(InvalidLiveQueryException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LiveErrorCode.INVALID_ADMIN_ROOM_QUERY));
        then(liveRoomRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("from 이 to 와 같거나 뒤 — LIVE-020, 조회하지 않는다")
    void invertedPeriodRejected() {
        for (Instant to : List.of(AT, AT.minusSeconds(1))) {
            assertThatThrownBy(() -> service.getRooms(new GetAdminRoomsQuery(null, null, AT, to, null, null)))
                    .isInstanceOfSatisfying(InvalidLiveQueryException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(LiveErrorCode.INVALID_ADMIN_ROOM_QUERY));
        }
        then(liveRoomRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("한쪽 끝만 있는 기간은 받는다")
    void halfOpenPeriodAccepted() {
        given(liveRoomRepository.findAdminRooms(any(), anyInt(), anyInt())).willReturn(List.of());

        service.getRooms(new GetAdminRoomsQuery(null, null, AT, null, null, null));
        service.getRooms(new GetAdminRoomsQuery(null, null, null, AT, null, null));

        then(liveRoomRepository).should().countAdminRooms(new AdminRoomFilter(LiveRoomTabs.ALL, null, AT, null));
        then(liveRoomRepository).should().countAdminRooms(new AdminRoomFilter(LiveRoomTabs.ALL, null, null, AT));
    }

    @Test
    @DisplayName("오프셋이 int 를 넘는 page — 목록은 비우고 조회하지 않는다, 총 개수는 센다")
    void offsetOverflowIsEmpty() {
        given(liveRoomRepository.countAdminRooms(any())).willReturn(3L);

        OffsetPage<AdminLiveRoomSummary> page = service.getRooms(
                new GetAdminRoomsQuery(null, null, null, null, Integer.MAX_VALUE, 100));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(3L);
        then(liveRoomRepository).should(never()).findAdminRooms(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("취소된 방 — 관리자 종료한 방은 시작·끊긴 시각이 보이고, 판매자 예약 취소 방은 둘 다 없다")
    void cancelledSummaryTimes() {
        LiveRoom adminEnded = room(new LiveStatus.Cancelled(AT, AT.plusSeconds(10), AT.plusSeconds(100)));
        LiveRoom sellerCancelled = room(new LiveStatus.Cancelled(AT, AT.minusSeconds(3600)));
        given(liveRoomRepository.findAdminRooms(any(), anyInt(), anyInt()))
                .willReturn(List.of(adminEnded, sellerCancelled));
        given(liveRoomRepository.countAdminRooms(any())).willReturn(2L);

        List<AdminLiveRoomSummary> rows = service.getRooms(
                new GetAdminRoomsQuery(LiveRoomTab.CANCELLED, null, null, null, null, null)).content();

        assertThat(rows).containsExactly(
                new AdminLiveRoomSummary(adminEnded.id(), sellerId, "닉", "제목", null, LiveRoomTab.CANCELLED,
                        AT, AT.plusSeconds(10), AT.plusSeconds(100)),
                new AdminLiveRoomSummary(sellerCancelled.id(), sellerId, "닉", "제목", null, LiveRoomTab.CANCELLED,
                        AT, null, null));
    }
}
