package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ExpireOrphanLiveUseCase;

/**
 * Ready 고착 방 만료 오케스트레이션.
 *
 * <p>방 1건의 전이·정리는 {@code ExpireOrphanLiveServiceTest} 가 담당한다. 여기서 고정하는 건
 * <b>임계 시각 계산</b>과 <b>한 방의 실패가 회차를 죽이지 않는지</b> 둘이다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileExpiredReadyServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(60);

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private ExpireOrphanLiveUseCase expireOrphanLiveUseCase;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private GoLiveByRtmpService goLiveByRtmpService;

    @Mock
    private TimeProvider timeProvider;

    private final RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();

    private ReconcileExpiredReadyService service;

    @BeforeEach
    void setup() {
        service = new ReconcileExpiredReadyService(
                liveRoomRepository, liveMediaManager, expireOrphanLiveUseCase, goLiveByRtmpService,
                new ExpiredReadyReconcilePolicy(THRESHOLD, 100), timeProvider, liveMetrics);
    }

    /** 후보만 있고 송출 중인 방은 없는 기본 상태. */
    private void givenCandidates(UUID... ids) {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(ids));
        if (ids.length > 0) {
            given(liveMediaManager.listRoomIngress(org.mockito.ArgumentMatchers.any(UUID.class)))
                    .willReturn(List.of());
            // 승격 판정이 방에게 묻는 구조라 기본 상태에도 방이 필요하다. WebRtc 방이면 ingress 가 없는 게
            // 정상이라 오설정 가드에 걸리지 않고 그대로 만료된다.
            for (UUID id : ids) {
                // lenient: 회차가 중간에 끊기는 테스트에서는 뒤 후보의 방을 읽지 않는다(그게 검증 대상이다)
                lenient().when(liveRoomRepository.findById(id)).thenReturn(java.util.Optional.of(
                        LiveRoom.builder().id(id).status(new LiveStatus.Ready(NOW.minus(THRESHOLD))).build()));
            }
        }
    }

    /** ingress 가 없는 게 정상인 WebRtc 방 — 오설정 가드에 걸리지 않고 만료된다. */
    private void givenWebRtcRoom(UUID roomId) {
        given(liveRoomRepository.findById(roomId)).willReturn(java.util.Optional.of(
                LiveRoom.builder().id(roomId).status(new LiveStatus.Ready(NOW.minus(THRESHOLD))).build()));
    }

    private static IngressSummary ing(String id, boolean publishing) {
        return new IngressSummary(id, "room", publishing);
    }

    /** 해당 ingressId 를 배정받은 Ready+RTMP 방. 승격 판정은 "방이 그 ingress 를 인정하는가"라 방이 필요하다. */
    private void givenRoomWithIngress(UUID roomId, String ingressId) {
        LiveRoom room = LiveRoom.builder()
                .id(roomId)
                .status(new LiveStatus.Ready(NOW.minus(THRESHOLD)))
                .streamType(new LiveStreamType.Rtmp(ingressId))
                .build();
        given(liveRoomRepository.findById(roomId)).willReturn(java.util.Optional.of(room));
    }

    @Test
    @DisplayName("임계 시각은 now - threshold 다 — 부호가 뒤집히거나 다른 정책 값이 들어오면 여기서 걸린다")
    void threshold_isNowMinusPolicy() {
        UUID roomId = UUID.randomUUID();
        givenCandidates(roomId);

        service.reconcile();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        then(liveRoomRepository).should().findExpiredReadyRoomIds(captor.capture(), anyInt());
        assertThat(captor.getValue()).isEqualTo(NOW.minus(THRESHOLD));
    }

    @Test
    @DisplayName("배치 크기는 정책 값을 그대로 넘긴다")
    void batchSize_comesFromPolicy() {
        givenCandidates();

        service.reconcile();

        then(liveRoomRepository).should().findExpiredReadyRoomIds(any(Instant.class), org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    @DisplayName("후보를 모두 만료시킨다")
    void allCandidates_areExpired() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        givenCandidates(first, second);

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(first));
        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(second));
    }

    @Test
    @DisplayName("한 방이 이미 Live 가 됐어도(조회~잠금 경합) 나머지 방 처리는 계속한다")
    void alreadyLiveRoom_isSkippedAndLoopContinues() {
        UUID conflicted = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        givenCandidates(conflicted, other);
        willThrow(new InvalidLiveStateException(conflicted.toString()))
                .given(expireOrphanLiveUseCase).expire(new ExpireOrphanLiveCommand(conflicted));

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(other));
    }

    @Test
    @DisplayName("방이 사라졌어도 회차는 계속된다")
    void missingRoom_isSkippedAndLoopContinues() {
        UUID missing = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        givenCandidates(missing, other);
        willThrow(new LiveNotFoundException(missing.toString()))
                .given(expireOrphanLiveUseCase).expire(new ExpireOrphanLiveCommand(missing));

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(other));
    }

    @Test
    @DisplayName("도메인 예외가 아닌 실패(DB 장애 등)는 회차를 중단시킨다 — 계속 돌아봐야 전부 실패한다")
    void unexpectedFailure_abortsRound() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        givenCandidates(first, second);
        willThrow(new IllegalStateException("DB 장애"))
                .given(expireOrphanLiveUseCase).expire(new ExpireOrphanLiveCommand(first));

        try {
            service.reconcile();
        } catch (IllegalStateException expected) {
            // 전파가 의도다
        }

        then(expireOrphanLiveUseCase).should(never()).expire(new ExpireOrphanLiveCommand(second));
    }

    @Test
    @DisplayName("송출 중인 방은 만료하지 않고 Live 로 승격한다 — 만료하면 판매자 송출이 끊긴다")
    void publishingRoom_isPromotedNotExpired() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of(ing("ing-1", true)));
        givenRoomWithIngress(roomId, "ing-1");

        service.reconcile();

        // webhook 과 같은 진입점으로, 확인한 ingressId 를 실어 보낸다
        then(goLiveByRtmpService).should(times(1)).goLiveByRtmp(roomId, "ing-1", PromotionTrigger.RECONCILE);
        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }

    @Test
    @DisplayName("ingress 는 있지만 송출 중이 아니면 그대로 만료한다")
    void idleIngress_isStillExpired() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of());
        givenWebRtcRoom(roomId);

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(roomId));
        then(goLiveByRtmpService).should(never()).goLiveByRtmp(any(UUID.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("다른 방의 송출 여부는 이 방 판정에 영향을 주지 않는다")
    void otherRoomPublishing_doesNotProtect() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of());
        givenWebRtcRoom(roomId);

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(roomId));
    }

    @Test
    @DisplayName("송출 조회가 실패한 방은 만료하지 않는다 — false 로 처리하면 송출 중인 방까지 끊는다")
    void ingressLookupFailure_expiresNothing() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listRoomIngress(roomId)).willThrow(new LiveMediaException("조회 실패"));

        service.reconcile();

        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
        then(goLiveByRtmpService).should(never()).goLiveByRtmp(any(UUID.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("한 방의 송출 조회가 실패해도 나머지 후보는 처리한다 — 후보가 오래된 순이라 선두가 막으면 뒤가 영영 안 돈다")
    void lookupFailureOnOneRoom_doesNotBlockTheRest() {
        UUID broken = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt()))
                .willReturn(List.of(broken, other));
        given(liveMediaManager.listRoomIngress(broken)).willThrow(new LiveMediaException("조회 실패"));
        given(liveMediaManager.listRoomIngress(other)).willReturn(List.of());
        givenWebRtcRoom(other);

        service.reconcile();

        then(expireOrphanLiveUseCase).should(never()).expire(new ExpireOrphanLiveCommand(broken));
        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(other));
    }

    @Test
    @DisplayName("송출 여부는 방을 처리하기 직전에 방마다 확인한다 — 회차 시작 스냅샷이면 그 뒤 재연결한 방이 만료된다")
    void publishingIsCheckedPerRoom_notOncePerRound() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt()))
                .willReturn(List.of(first, second));
        // 첫 방 처리 중에 둘째 방이 재연결한 상황
        given(liveMediaManager.listRoomIngress(first)).willReturn(List.of());
        givenWebRtcRoom(first);
        given(liveMediaManager.listRoomIngress(second)).willReturn(List.of(ing("ing-2", true)));
        givenRoomWithIngress(second, "ing-2");

        service.reconcile();

        then(liveMediaManager).should(times(1)).listRoomIngress(first);
        then(liveMediaManager).should(times(1)).listRoomIngress(second);
        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(first));
        then(expireOrphanLiveUseCase).should(never()).expire(new ExpireOrphanLiveCommand(second));
    }

    @Test
    @DisplayName("방이 인정하지 않는 ingress 가 송출 중이면 승격도 만료도 하지 않는다 — 경합 패자 잔존")
    void foreignIngressPublishing_isNeitherPromotedNorExpired() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of(ing("ing-LOSER", true)));
        givenRoomWithIngress(roomId, "ing-WINNER");

        service.reconcile();

        // 승격하면 방이 인정 안 한 ingress 가 방송을 시작하고, 만료하면 그 송출을 끊는다
        then(goLiveByRtmpService).should(never()).goLiveByRtmp(any(UUID.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }

    @Test
    @DisplayName("송출 중 ingress 가 여럿이어도 방이 인정하는 것을 골라 승격한다 — 하나만 봤으면 오판한다")
    void multiplePublishingIngresses_picksTheAcknowledgedOne() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        // 방의 것이 목록 선두가 아니다 — 단건 비교로는 "송출 안 함"이 되어 살아 있는 방송을 끊는다
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of(ing("ing-LOSER", true), ing("ing-WINNER", true)));
        givenRoomWithIngress(roomId, "ing-WINNER");

        service.reconcile();

        then(goLiveByRtmpService).should(times(1)).goLiveByRtmp(roomId, "ing-WINNER", PromotionTrigger.RECONCILE);
        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }

    @Test
    @DisplayName("RTMP 방인데 LiveKit 이 ingress 를 하나도 모르면 만료하지 않는다 — 200+빈 목록 오설정")
    void rtmpRoomWithNoIngressKnownToLiveKit_isNotExpired() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of());
        givenRoomWithIngress(roomId, "ing-1");

        service.reconcile();

        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }

    @Test
    @DisplayName("OBS 가 끝내 안 붙은 RTMP 방은 정상 만료한다 — ingress 는 등록돼 있고 송출만 없다")
    void rtmpRoomWithRegisteredButIdleIngress_isExpired() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        // 위 오설정 가드와 갈리는 지점: 목록이 비지 않았다
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of(ing("ing-1", false)));
        givenRoomWithIngress(roomId, "ing-1");

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(roomId));
    }

    @Test
    @DisplayName("후보가 없으면 LiveKit 을 조회하지도 않는다")
    void noCandidates_doesNothing() {
        givenCandidates();

        service.reconcile();

        then(liveMediaManager).should(never()).listRoomIngress(any(UUID.class));
        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }

    @Test
    @DisplayName("LiveKit 이 방의 ingress 를 모르는 스킵은 회차 중단이 아니라 별도 갈래로 센다 — 방 단위로 aborted 를 올리면 회차 지표가 깨진다")
    void ingressMissing_countsAsOwnActionNotAbortedRound() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt()))
                .willReturn(List.of(first, second));
        given(liveMediaManager.listRoomIngress(any(UUID.class))).willReturn(List.of());
        givenRoomWithIngress(first, "ing-1");
        givenRoomWithIngress(second, "ing-2");

        service.reconcile();

        org.assertj.core.api.Assertions.assertThat(liveMetrics.abortedRounds).isEmpty();
        org.assertj.core.api.Assertions.assertThat(liveMetrics.completedRounds)
                .containsExactly(ReconcileJob.EXPIRE_READY);
        org.assertj.core.api.Assertions.assertThat(liveMetrics.acted)
                .contains("SKIPPED_INGRESS_MISSING=2")
                .doesNotContain("SKIPPED=2");
    }

    @Test
    @DisplayName("예외로 죽은 회차는 이 잡의 이름으로 failed 를 센다 — 세 잡이 같은 래퍼를 복사한 구조라 잡 이름이 어긋나기 쉽다")
    void failedRound_isCountedWithOwnJobName() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt()))
                .willThrow(new IllegalStateException("DB 장애"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(IllegalStateException.class);

        org.assertj.core.api.Assertions.assertThat(liveMetrics.failedRounds)
                .containsExactly(ReconcileJob.EXPIRE_READY);
        org.assertj.core.api.Assertions.assertThat(liveMetrics.completedRounds).isEmpty();
    }
}
