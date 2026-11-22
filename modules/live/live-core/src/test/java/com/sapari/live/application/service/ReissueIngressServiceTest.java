package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ReissueIngressCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LivePermissionDeniedException;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.IngressCredentialView;

@ExtendWith(MockitoExtension.class)
@DisplayName("송출 키 재발급 — Scheduled RTMP 키 있는 방만, CAS 로 하나만 승리")
class ReissueIngressServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID ROOM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELLER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ReissueIngressCommand COMMAND = new ReissueIngressCommand(ROOM, SELLER);

    @Mock private LiveRoomRepository liveRoomRepository;
    @Mock private LiveMediaManager liveMediaManager;
    @Mock private RtmpIngressAssigner rtmpIngressAssigner;
    @Mock private TimeProvider timeProvider;
    @Mock private SellerLivePermissionGuard sellerLivePermissionGuard;
    @InjectMocks private ReissueIngressService service;

    private static LiveRoom room(LiveStatus status, LiveStreamType streamType) {
        return LiveRoom.builder().id(ROOM).sellerId(SELLER).title("제목").sellerNickname("닉")
                .status(status).streamType(streamType).rtmpUrl("rtmp://old").scheduledAt(AT).build();
    }

    private void givenRoom(LiveRoom room) {
        given(liveRoomRepository.findByIdAndSellerId(ROOM, SELLER)).willReturn(Optional.of(room));
    }

    @Test
    @DisplayName("성공 — 새 ingress 생성 → CAS(이전 id 조건) → 이전 ingress 단건 삭제 → 새 자격 반환")
    void reissues() {
        givenRoom(room(new LiveStatus.Scheduled(AT), new LiveStreamType.Rtmp("ing-OLD")));
        given(liveMediaManager.createIngress(ROOM, SELLER)).willReturn(new IngressResult("ing-NEW", "rtmp://new", "key-NEW"));
        given(timeProvider.now()).willReturn(NOW);
        given(rtmpIngressAssigner.reassignIfCurrent(ROOM, SELLER, "ing-OLD", "ing-NEW", "rtmp://new", NOW)).willReturn(true);

        IngressCredentialView view = service.reissue(COMMAND);

        assertThat(view).isEqualTo(new IngressCredentialView("ing-NEW", "rtmp://new", "key-NEW"));
        then(liveMediaManager).should().deleteIngress(ROOM, "ing-OLD");
        then(liveMediaManager).should(never()).deleteIngress(ROOM, "ing-NEW");
        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class));
        then(liveRoomRepository).should(never()).save(any());
    }

    private void givenReissueWins() {
        givenRoom(room(new LiveStatus.Scheduled(AT), new LiveStreamType.Rtmp("ing-OLD")));
        given(liveMediaManager.createIngress(ROOM, SELLER)).willReturn(new IngressResult("ing-NEW", "rtmp://new", "key-NEW"));
        given(timeProvider.now()).willReturn(NOW);
        given(rtmpIngressAssigner.reassignIfCurrent(ROOM, SELLER, "ing-OLD", "ing-NEW", "rtmp://new", NOW)).willReturn(true);
    }

    private static IngressSummary summary(String ingressId) {
        return new IngressSummary(ingressId, ROOM.toString(), false);
    }

    @Test
    @DisplayName("이전 키가 첫 삭제 뒤에도 남아 있으면 한 번 더 지운다")
    void retriesOldKeyDeleteOnce() {
        givenReissueWins();
        given(liveMediaManager.listRoomIngress(ROOM))
                .willReturn(List.of(summary("ing-NEW"), summary("ing-OLD")))
                .willReturn(List.of(summary("ing-NEW")));

        service.reissue(COMMAND);

        then(liveMediaManager).should(times(2)).deleteIngress(ROOM, "ing-OLD");
    }

    @Test
    @DisplayName("두 번 지워도 남으면 더 시도하지 않고 새 자격은 그대로 반환 — 실패를 던지면 판매자가 새 키를 잃는다")
    void returnsNewKeyWhenOldKeySurvives() {
        givenReissueWins();
        given(liveMediaManager.listRoomIngress(ROOM)).willReturn(List.of(summary("ing-NEW"), summary("ing-OLD")));

        IngressCredentialView view = service.reissue(COMMAND);

        assertThat(view).isEqualTo(new IngressCredentialView("ing-NEW", "rtmp://new", "key-NEW"));
        then(liveMediaManager).should(times(2)).deleteIngress(ROOM, "ing-OLD");
    }

    @Test
    @DisplayName("소멸 확인 조회가 실패해도 새 자격은 반환하고 재시도하지 않는다")
    void returnsNewKeyWhenVerificationFails() {
        givenReissueWins();
        given(liveMediaManager.listRoomIngress(ROOM)).willThrow(new LiveMediaException("조회 실패"));

        IngressCredentialView view = service.reissue(COMMAND);

        assertThat(view).isEqualTo(new IngressCredentialView("ing-NEW", "rtmp://new", "key-NEW"));
        then(liveMediaManager).should(times(1)).deleteIngress(ROOM, "ing-OLD");
    }

    @Test
    @DisplayName("CAS 패배 — 자기가 만든 새 ingress 만 회수하고 LIVE-018, 이전 ingress 는 그대로")
    void losesRace() {
        givenRoom(room(new LiveStatus.Scheduled(AT), new LiveStreamType.Rtmp("ing-OLD")));
        given(liveMediaManager.createIngress(ROOM, SELLER)).willReturn(new IngressResult("ing-NEW", "rtmp://new", "key-NEW"));
        given(timeProvider.now()).willReturn(NOW);
        given(rtmpIngressAssigner.reassignIfCurrent(ROOM, SELLER, "ing-OLD", "ing-NEW", "rtmp://new", NOW)).willReturn(false);

        assertRejected(LiveErrorCode.INGRESS_REISSUE_NOT_ALLOWED);

        then(liveMediaManager).should().deleteIngress(ROOM, "ing-NEW");
        then(liveMediaManager).should(never()).deleteIngress(ROOM, "ing-OLD");
    }

    static Stream<LiveRoom> rejectedRooms() {
        return Stream.of(
                room(new LiveStatus.Scheduled(AT), new LiveStreamType.WebRtc()),
                room(new LiveStatus.Scheduled(AT), new LiveStreamType.Rtmp(null)),
                room(new LiveStatus.Ready(AT), new LiveStreamType.Rtmp("ing-OLD")),
                room(new LiveStatus.Live(NOW, "sfu", "eg", "https://hls"), new LiveStreamType.Rtmp("ing-OLD")),
                room(new LiveStatus.Ended(NOW, NOW, null), new LiveStreamType.Rtmp("ing-OLD")),
                room(new LiveStatus.Cancelled(AT, NOW), new LiveStreamType.Rtmp("ing-OLD")));
    }

    @ParameterizedTest
    @MethodSource("rejectedRooms")
    @DisplayName("가드 — WebRTC / 키 없는 RTMP / Scheduled 아님은 LIVE-018, 미디어 호출 없음")
    void rejects(LiveRoom room) {
        givenRoom(room);

        assertRejected(LiveErrorCode.INGRESS_REISSUE_NOT_ALLOWED);

        then(liveMediaManager).shouldHaveNoInteractions();
        then(rtmpIngressAssigner).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("남의 방·없는 방 — 404, 미디어 호출 없음")
    void notOwned() {
        given(liveRoomRepository.findByIdAndSellerId(ROOM, SELLER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissue(COMMAND)).isInstanceOf(LiveNotFoundException.class);
        then(liveMediaManager).shouldHaveNoInteractions();
    }

    private void assertRejected(LiveErrorCode code) {
        assertThatThrownBy(() -> service.reissue(COMMAND))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("라이브 권한이 꺼진 판매자 — LIVE-019, 재발급 없음")
    void blockedSellerIsRejectedBeforeAnything() {
        org.mockito.BDDMockito.willThrow(new LivePermissionDeniedException())
                .given(sellerLivePermissionGuard).verifyLiveEnabled(SELLER);

        assertThatThrownBy(() -> service.reissue(COMMAND))
                .isInstanceOf(LivePermissionDeniedException.class);

        org.mockito.Mockito.verifyNoInteractions(liveRoomRepository, liveMediaManager, rtmpIngressAssigner);
    }
}
