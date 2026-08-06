package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.OrphanMediaReconcilePolicy;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * 고아 미디어 회수 판정.
 *
 * <p>이 서비스의 오판은 <b>살아 있는 방송을 끊는다</b>. 그래서 "지운다" 못지않게 "지우지 않는다" 케이스를
 * 촘촘히 고정한다 — 판정이 애매하면 남겨두는 게 계약이다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileOrphanMediaServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");
    /** NOW - grace(15m). 이보다 오래된 것만 회수 대상. */
    private static final Instant OLD = Instant.parse("2026-06-10T11:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-06-10T11:55:00Z");

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private TimeProvider timeProvider;

    private ReconcileOrphanMediaService service;

    private UUID roomId;

    @BeforeEach
    void setup() {
        service = new ReconcileOrphanMediaService(
                liveMediaManager, liveRoomRepository,
                new OrphanMediaReconcilePolicy(Duration.ofMinutes(15)), timeProvider);
        roomId = UUID.randomUUID();
    }

    private LiveRoom room(LiveStatus status, LiveStreamType streamType, Instant updatedAt) {
        return LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(status)
                .streamType(streamType)
                .updatedAt(updatedAt)
                .build();
    }

    private LiveStatus.Ended ended() {
        return new LiveStatus.Ended(Instant.parse("2026-06-10T09:00:00Z"), OLD, null);
    }

    private LiveStatus.Live live() {
        return new LiveStatus.Live(Instant.parse("2026-06-10T09:00:00Z"), "sfu-1", "eg-1", "https://hls/1");
    }

    /** 목록 스텁 — 두 조회는 매 회차 반드시 불린다. */
    private void givenLiveKit(List<IngressSummary> ingresses, List<EgressSummary> egresses) {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress()).willReturn(ingresses);
        given(liveMediaManager.listAllEgress()).willReturn(egresses);
    }

    // ---------- ingress ----------

    @Test
    @DisplayName("ingress — 종료된 방의 ingress 는 삭제한다 (종료 정리가 실패해 남은 잔여물)")
    void ingress_endedRoom_isDeleted() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-1");
    }

    @Test
    @DisplayName("ingress — 진행 중인 방의 정본 ingress 는 건드리지 않는다")
    void ingress_currentIngressOfOpenRoom_isKept() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("ingress — DB 가 아는 id 와 다르면 삭제한다 (중복 prepare 로 생긴 고아)")
    void ingress_mismatchedId_isDeleted() {
        givenLiveKit(List.of(new IngressSummary("ing-DUP", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-DUP");
    }

    @Test
    @DisplayName("ingress — 아직 끝나지 않은 방이 송출 중이면 DB 가 고아라 해도 지우지 않는다")
    void ingress_publishingOnOpenRoom_isNeverDeleted() {
        givenLiveKit(List.of(new IngressSummary("ing-DUP", roomId.toString(), true)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("ingress — 종료된 방의 송출은 잔재다: 종료 정리가 실패했을 때 회수할 주체가 여기뿐이다")
    void ingress_publishingOnEndedRoom_isDeleted() {
        // 종료 시 deleteIngress 가 실패하면 판매자는 이미 받은 streamKey 로 계속 송출할 수 있다.
        // publishing 이라고 여기서도 건너뛰면 egress 과금이 이어지고 좀비 방이 남는다.
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), true)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-1");
    }

    @Test
    @DisplayName("ingress — 유예가 지나지 않았으면 지우지 않는다 (createIngress 직후 save 전인 정상 요청)")
    void ingress_withinGrace_isKept() {
        givenLiveKit(List.of(new IngressSummary("ing-DUP", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), RECENT)));

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("ingress — DB 에 방이 없으면 로그만 남기고 지우지 않는다")
    void ingress_roomNotInDb_isKept() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId))).willReturn(List.of());

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("roomName 이 roomId 형식이 아니면 대상에서 제외한다 (우리 방이 아님)")
    void nonUuidRoomName_isSkipped() {
        givenLiveKit(List.of(new IngressSummary("ing-1", "not-a-uuid", false)),
                List.of(new EgressSummary("eg-1", "not-a-uuid", true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of())).willReturn(List.of());

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
        then(liveMediaManager).should(never()).stopHlsEgress(any(UUID.class));
    }

    // ---------- egress ----------

    @Test
    @DisplayName("egress — Live 가 아닌 방의 활성 egress 는 중단한다 (DB 는 끝났는데 인코딩 과금이 계속된다)")
    void egress_nonLiveRoom_isStopped() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    @Test
    @DisplayName("egress — Live 인 방의 egress 는 정상이므로 중단하지 않는다")
    void egress_liveRoom_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(live(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopHlsEgress(any(UUID.class));
    }

    @Test
    @DisplayName("egress — startedAt 이 없으면 나이를 알 수 없어 건드리지 않는다")
    void egress_withoutStartedAt_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), true, null)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopHlsEgress(any(UUID.class));
    }

    @Test
    @DisplayName("egress — 이미 멈춘 egress 는 대상이 아니다 (비용이 나가지 않는다)")
    void egress_inactive_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), false, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopHlsEgress(any(UUID.class));
    }

    @Test
    @DisplayName("egress — 한 방에 화질별 egress 가 여러 건이어도 방 단위 중단은 1회만 부른다")
    void egress_sameRoomMultipleEgresses_stopsOnce() {
        givenLiveKit(List.of(), List.of(
                new EgressSummary("eg-1080", roomId.toString(), true, OLD),
                new EgressSummary("eg-720", roomId.toString(), true, OLD),
                new EgressSummary("eg-360", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    // ---------- 조회 실패 ----------

    @Test
    @DisplayName("LiveKit 조회가 실패하면 회차 전체를 실패시킨다 — 빈 목록을 '고아 없음'으로 오독하면 안 된다")
    void listFailure_propagates() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress()).willThrow(new LiveMediaException("조회 실패"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(LiveMediaException.class);

        then(liveRoomRepository).should(never()).findAllByIds(any());
        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }
}
