package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.live.command.GetSellerRoomDetailCommand;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.PublicLiveRoomDetailView;
import com.sapari.live.view.SellerLiveRoomDetailView;

/** 의존이 리포지토리 하나뿐이다 — 상세는 LiveKit 을 부르지 않는다(생성자에 미디어 포트가 없다). */
@ExtendWith(MockitoExtension.class)
@DisplayName("방 상세 — 공개/판매자, 모든 상태")
class GetLiveRoomDetailServiceTest {

    private static final Instant AT = Instant.parse("2026-06-10T10:00:00Z");

    @Mock private LiveRoomRepository liveRoomRepository;
    @InjectMocks private GetLiveRoomDetailService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    private LiveRoom room(LiveStatus status) {
        return LiveRoom.builder().id(roomId).sellerId(sellerId).title("제목").description("설명")
                .thumbnailUrl("https://cdn/t.jpg").sellerNickname("닉").status(status)
                .streamType(new LiveStreamType.Rtmp("ing-1")).rtmpUrl("rtmp://ingress/x")
                .vodStatus(VodStatus.NONE).vodPublic(true).scheduledAt(AT).build();
    }

    @Test
    @DisplayName("공개 상세 — 종료된 방: 탭 상태·시각·vodStatus")
    void publicDetailOfEndedRoom() {
        given(liveRoomRepository.findById(roomId))
                .willReturn(Optional.of(room(new LiveStatus.Ended(AT.plusSeconds(1), AT.plusSeconds(9), null))));

        PublicLiveRoomDetailView view = service.getPublicDetail(roomId);

        assertThat(view).isEqualTo(new PublicLiveRoomDetailView("제목", "설명", "https://cdn/t.jpg", "닉",
                LiveRoomTab.ENDED, AT, AT.plusSeconds(1), AT.plusSeconds(9), "NONE"));
    }

    @Test
    @DisplayName("공개 상세 — 취소된 방도 보인다(존재를 숨기지 않는다), 종료 시각 없음")
    void publicDetailOfCancelledRoom() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(room(new LiveStatus.Cancelled(AT, AT))));

        PublicLiveRoomDetailView view = service.getPublicDetail(roomId);

        assertThat(view.status()).isEqualTo(LiveRoomTab.CANCELLED);
        assertThat(view.endedAt()).isNull();
        assertThat(view.scheduledAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("공개 상세 — 없는 방 404")
    void publicDetailNotFound() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublicDetail(roomId)).isInstanceOf(LiveNotFoundException.class);
    }

    @Test
    @DisplayName("판매자 상세 — 소유권 쿼리로 조회, rtmpUrl 포함")
    void sellerDetail() {
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Ready(AT))));

        SellerLiveRoomDetailView view = service.getSellerDetail(new GetSellerRoomDetailCommand(roomId, sellerId));

        assertThat(view.status()).isEqualTo(LiveRoomTab.SCHEDULED);
        assertThat(view.rtmpUrl()).isEqualTo("rtmp://ingress/x");
    }

    @Test
    @DisplayName("판매자 상세 — 남의 방은 없는 방과 같은 404")
    void sellerDetailOfForeignRoom() {
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSellerDetail(new GetSellerRoomDetailCommand(roomId, sellerId)))
                .isInstanceOf(LiveNotFoundException.class);
    }

    @Test
    @DisplayName("공개 상세 — 다시보기 비공개 방은 vodStatus 를 숨긴다, 판매자 상세는 본다")
    void vodStatusHiddenWhenVodPrivate() {
        LiveRoom room = room(new LiveStatus.Ended(AT, AT, null)).toBuilder().vodPublic(false).build();
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(room));
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));

        assertThat(service.getPublicDetail(roomId).vodStatus()).isNull();
        assertThat(service.getSellerDetail(new GetSellerRoomDetailCommand(roomId, sellerId)).vodStatus()).isEqualTo("NONE");
    }
}
