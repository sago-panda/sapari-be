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
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.UnsupportedRoleException;
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
                        sellerId, "타이틀", "설명", "판매자닉", "http://thumb", now.plusSeconds(3600), now)
                .toBuilder().id(roomId).build();
        // Live 상태로 전이 + hlsUrl 세팅
        liveRoom = scheduled.startLive(StreamInfo.of("sfu-room", "egress-1", "http://cdn/master.m3u8", null), now);
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
    @DisplayName("관리자(ADMIN)가 입장하면 owner=false, role=ADMIN으로 발급한다")
    void issuesAdminToken_whenAdmin() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));
        given(roomTokenIssuer.issue(org.mockito.ArgumentMatchers.any())).willReturn("signed-token");

        EnterLiveCommand command = new EnterLiveCommand(
                roomId, UUID.randomUUID(), "ADMIN", "관리자", "admin@sapari.com");
        enterLiveService.enter(command);

        ArgumentCaptor<RoomTokenClaims> captor = ArgumentCaptor.forClass(RoomTokenClaims.class);
        verify(roomTokenIssuer).issue(captor.capture());
        RoomTokenClaims claims = captor.getValue();

        assertThat(claims.owner()).isFalse();
        assertThat(claims.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("미인증(게스트) 입장은 에페메랄 GUEST 토큰(owner=false, 에페메랄 userId)을 발급한다")
    void issuesGuestToken_whenUnauthenticated() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));
        given(roomTokenIssuer.issue(org.mockito.ArgumentMatchers.any())).willReturn("guest-token");

        EnterLiveCommand command = new EnterLiveCommand(roomId, null, null, null, null);
        EnterLiveView view = enterLiveService.enter(command);

        ArgumentCaptor<RoomTokenClaims> captor = ArgumentCaptor.forClass(RoomTokenClaims.class);
        verify(roomTokenIssuer).issue(captor.capture());
        RoomTokenClaims claims = captor.getValue();

        assertThat(claims.role()).isEqualTo("GUEST");
        assertThat(claims.owner()).isFalse();
        assertThat(claims.userId()).isNotNull();          // 에페메랄 id 부여
        assertThat(claims.nickname()).isNull();
        assertThat(claims.email()).isNull();
        assertThat(claims.roomId()).isEqualTo(roomId);
        assertThat(view.roomToken()).isEqualTo("guest-token");
        assertThat(view.hlsUrl()).isEqualTo("http://cdn/master.m3u8");
    }

    @Test
    @DisplayName("게스트 토큰의 userId는 매 입장마다 새로 부여된다(에페메랄)")
    void guestUserId_isEphemeral() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));
        given(roomTokenIssuer.issue(org.mockito.ArgumentMatchers.any())).willReturn("guest-token");
        EnterLiveCommand command = new EnterLiveCommand(roomId, null, null, null, null);

        enterLiveService.enter(command);
        enterLiveService.enter(command);

        ArgumentCaptor<RoomTokenClaims> captor = ArgumentCaptor.forClass(RoomTokenClaims.class);
        verify(roomTokenIssuer, org.mockito.Mockito.times(2)).issue(captor.capture());
        assertThat(captor.getAllValues().get(0).userId())
                .isNotEqualTo(captor.getAllValues().get(1).userId());
    }

    @Test
    @DisplayName("인증됐지만 role이 null이면 UnsupportedRoleException으로 실패한다(NPE 방지·fail-closed)")
    void rejects_whenRoleNull() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));

        EnterLiveCommand command = new EnterLiveCommand(roomId, UUID.randomUUID(), null, "닉", "e@e.com");

        assertThatThrownBy(() -> enterLiveService.enter(command))
                .isInstanceOf(UnsupportedRoleException.class);
        verify(roomTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("지원하지 않는 role은 원본 값을 노출하지 않고 UnsupportedRoleException으로 실패한다")
    void rejects_whenUnsupportedRole() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.of(liveRoom));

        EnterLiveCommand command = new EnterLiveCommand(roomId, UUID.randomUUID(), "UNKNOWN", "닉", "e@e.com");

        assertThatThrownBy(() -> enterLiveService.enter(command))
                .isInstanceOf(UnsupportedRoleException.class)
                .hasMessageNotContaining("UNKNOWN");
        verify(roomTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("RoomTokenClaims.toString()은 email/nickname을 마스킹한다")
    void roomTokenClaimsToStringMasksPii() {
        RoomTokenClaims claims = new RoomTokenClaims(
                UUID.randomUUID(), roomId, "SELLER", true, "판매자닉", "seller@sapari.com");

        String s = claims.toString();

        assertThat(s).contains("nickname=***", "email=***");
        assertThat(s).doesNotContain("seller@sapari.com", "판매자닉");
    }

    @Test
    @DisplayName("라이브 상태가 아니면 InvalidLiveStateException으로 입장을 거부한다")
    void rejects_whenNotLive() {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        UUID scheduledId = UUID.randomUUID();
        LiveRoom scheduled = LiveRoom.create(sellerId, "t", "d", "n", "http://th", now.plusSeconds(3600), now)
                .toBuilder().id(scheduledId).build(); // Scheduled
        given(liveRoomRepository.findById(scheduledId)).willReturn(Optional.of(scheduled));

        EnterLiveCommand command = new EnterLiveCommand(scheduledId, sellerId, "SELLER", "n", "e@e.com");

        assertThatThrownBy(() -> enterLiveService.enter(command))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_ENTER_NOT_ALLOWED);
        verify(roomTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    /**
     * ⭐ <b>{@code enter} 는 트랜잭션 안에서 돌지 않는다 — RS256 서명이 풀 커넥션을 쥐면
     * 점유시간이 처리량 천장이 된다.</b>
     *
     * <p>서명은 실측 685µs(RSA-2048)이고 Hikari 기본 최대 풀은 10 이다. 읽기를 트랜잭션으로
     * 감싸면 그 685µs 가 커넥션을 쥔 채 돌고, 고갈되면 피해자는 같은 풀을 쓰는
     * 상태 전이들의 행 잠금 읽기다({@code StartLiveService}·{@code EndLiveService} 의
     * {@code findByIdAndSellerIdForUpdate} 등 — 전수는 {@code EnterLiveService} javadoc).
     *
     * <p>⚠️ <b>이 단정이 재는 것은 애너테이션의 부재뿐이다.</b> 실제로 트랜잭션이 열리는지는
     * 트랜잭션 매니저가 있는 컨텍스트에서만 관측되고 이 클래스는 Mockito 단위 테스트다. 그래도
     * 벡터를 두는 이유는 이 결함의 모양이 <b>애너테이션을 다시 붙이는 것</b>이고, 그것을 막는
     * 다른 칸이 없기 때문이다(그 모양으로 선재 결함이 있었다). 메서드 시그니처가 바뀌면
     * {@code NoSuchMethodException} 으로 시끄럽게 깨진다.
     */
    @Test
    @DisplayName("⭐ enter 는 트랜잭션 안에서 돌지 않는다 — 서명이 커넥션을 쥐지 않게")
    void enterIsNotWrappedInATransaction() throws Exception {
        java.lang.reflect.Method enter = EnterLiveService.class
                .getDeclaredMethod("enter", EnterLiveCommand.class);

        assertThat(enter.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class))
                .as("트랜잭션을 다시 붙이면 RS256 서명(685µs)이 풀 커넥션을 점유한다. "
                        + "읽기만 감싸야 한다면 그 읽기를 별 빈으로 떼라 — 이 메서드에 붙이지 말고.")
                .isNull();
        assertThat(EnterLiveService.class.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class))
                .as("클래스에 붙이면 메서드에 붙인 것과 같다")
                .isNull();
    }
}
