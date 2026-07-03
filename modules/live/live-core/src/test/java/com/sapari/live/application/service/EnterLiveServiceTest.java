package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.EnterLiveView;

@ExtendWith(MockitoExtension.class)
class EnterLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;
    @Mock
    private RoomTokenIssuer roomTokenIssuer;
    @InjectMocks
    private EnterLiveService enterLiveService;

    private UUID roomId;
    private UUID sellerId;
    private LiveRoom liveRoom;

    @BeforeEach
    void setup() {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        sellerId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        LiveRoom scheduled = LiveRoom.create(
                        sellerId, "타이틀", "설명", "판매자닉", "http://thumb", now, now)
                .toBuilder().id(roomId).build();
        // Live 상태로 전이 + hlsUrl 세팅
        liveRoom = scheduled.startLive(StreamInfo.of("sfu-room", "egress-1", "http://cdn/master.m3u8"), now);
    }

    @Test
    @DisplayName("방주인(userId==sellerId)이 입장하면 owner=true로 룸 토큰을 발급한다")
    void issuesOwnerToken_whenSeller() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));
        given(roomTokenIssuer.issue(org.mockito.ArgumentMatchers.any())).willReturn("signed-token");

        EnterLiveCommand command = new EnterLiveCommand(roomId, sellerId, "SELLER", "판매자닉", "seller@sapari.com");
        EnterLiveView view = enterLiveService.enter(command);

        ArgumentCaptor<RoomTokenClaims> captor = ArgumentCaptor.forClass(RoomTokenClaims.class);
        verify(roomTokenIssuer).issue(captor.capture());
        RoomTokenClaims claims = captor.getValue();

        assertThat(claims.owner()).isTrue();
        assertThat(claims.role()).isEqualTo("SELLER");
        assertThat(claims.email()).isEqualTo("seller@sapari.com");
        assertThat(view.roomToken()).isEqualTo("signed-token");
        assertThat(view.hlsUrl()).isEqualTo("http://cdn/master.m3u8");
    }

    @Test
    @DisplayName("일반 회원(USER)이 입장하면 owner=false, role=BUYER로 매핑해 발급한다")
    void issuesBuyerToken_whenUser() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));
        given(roomTokenIssuer.issue(org.mockito.ArgumentMatchers.any())).willReturn("signed-token");

        EnterLiveCommand command = new EnterLiveCommand(
                roomId, UUID.randomUUID(), "USER", "구매자닉", "buyer@sapari.com");
        enterLiveService.enter(command);

        ArgumentCaptor<RoomTokenClaims> captor = ArgumentCaptor.forClass(RoomTokenClaims.class);
        verify(roomTokenIssuer).issue(captor.capture());
        RoomTokenClaims claims = captor.getValue();

        assertThat(claims.owner()).isFalse();
        assertThat(claims.role()).isEqualTo("BUYER");
    }

    @Test
    @DisplayName("미인증(게스트) 입장은 룸 토큰을 발급하지 않고 hlsUrl만 반환한다")
    void noToken_whenUnauthenticated() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));

        EnterLiveCommand command = new EnterLiveCommand(roomId, null, null, null, null);
        EnterLiveView view = enterLiveService.enter(command);

        assertThat(view.roomToken()).isNull();
        assertThat(view.hlsUrl()).isEqualTo("http://cdn/master.m3u8");
        verify(roomTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("라이브 상태가 아니면 InvalidLiveStateException으로 입장을 거부한다")
    void rejects_whenNotLive() {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        UUID scheduledId = UUID.randomUUID();
        LiveRoom scheduled = LiveRoom.create(sellerId, "t", "d", "n", "http://th", now, now)
                .toBuilder().id(scheduledId).build(); // Scheduled
        given(liveRoomRepository.findById(scheduledId)).willReturn(Optional.of(scheduled));

        EnterLiveCommand command = new EnterLiveCommand(scheduledId, sellerId, "SELLER", "n", "e@e.com");

        assertThatThrownBy(() -> enterLiveService.enter(command))
                .isInstanceOf(InvalidLiveStateException.class);
        verify(roomTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }
}
