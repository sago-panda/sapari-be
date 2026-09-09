package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.StaleLiveReconcilePolicy;
import com.sapari.live.command.EndStaleLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EndStaleLiveUseCase;

/**
 * 방치된 Live 방 판정.
 *
 * <p>오판이 <b>살아 있는 방송을 끊는다</b>. 경과 시간만으로 끊지 않는다는 것, 조회가 실패하면
 * 아무것도 끊지 않는다는 것 — 두 계약을 고정하는 게 이 테스트의 목적이다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileStaleLiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-06-10T09:00:00Z");

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private EndStaleLiveUseCase endStaleLiveUseCase;

    @Mock
    private TimeProvider timeProvider;

    private final RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();

    private ReconcileStaleLiveService service;

    private UUID roomId;

    @BeforeEach
    void setup() {
        service = new ReconcileStaleLiveService(
                liveRoomRepository, liveMediaManager, endStaleLiveUseCase,
                new StaleLiveReconcilePolicy(Duration.ofMinutes(60), 10, Duration.ofMinutes(20)), timeProvider, liveMetrics);
        roomId = UUID.randomUUID();
    }

    @Test
    @DisplayName("후보가 상한을 넘어도 한 회차에는 전용 batch-size 만큼만 처리한다")
    void candidatesOverLimit_processesOnlyBatchSize() {
        List<UUID> candidates = java.util.stream.IntStream.range(0, 11)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findStaleLiveRoomIds(any(Instant.class), anyInt()))
                .willReturn(candidates.subList(0, 10));
        given(liveMediaManager.listAllEgress()).willReturn(List.of(
                new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));

        service.reconcile();

        then(liveRoomRepository).should().findStaleLiveRoomIds(any(Instant.class), org.mockito.ArgumentMatchers.eq(11));
        then(endStaleLiveUseCase).should(times(10)).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("회차 예산을 넘기면 남은 후보를 다음 회차로 넘긴다 — 리스를 넘겨 겹쳐 도는 것보다 낫다")
    void roundBudgetExhausted_stopsMidRound() {
        // 후보당 왕복 수는 방의 화질 수에 비례해 유한하지 않다. batch-size 만으로는 회차가
        // lock-at-most-for 안에 있다는 보장이 없으므로 시계로도 끊는다.
        ReconcileStaleLiveService budgeted = new ReconcileStaleLiveService(
                liveRoomRepository, liveMediaManager, endStaleLiveUseCase,
                new StaleLiveReconcilePolicy(Duration.ofMinutes(60), 10, Duration.ofMinutes(1)),
                timeProvider, liveMetrics);
        List<UUID> candidates = java.util.stream.IntStream.range(0, 5)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        // 시작 → 첫 후보 직전(예산 내) → 그 뒤로는 예산 초과
        given(timeProvider.now()).willReturn(NOW, NOW, NOW.plus(Duration.ofMinutes(5)));
        given(liveRoomRepository.findStaleLiveRoomIds(any(Instant.class), anyInt())).willReturn(candidates);
        given(liveMediaManager.listAllEgress()).willReturn(List.of(
                new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));

        budgeted.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(any(EndStaleLiveCommand.class));
        // batch-size 포화와 섞이지 않아야 한다 — 처방이 정반대다.
        assertThat(liveMetrics.acted).contains("ROUND_BUDGET_EXHAUSTED=1");
        assertThat(liveMetrics.acted).contains("STALE_LIVE_BATCH_SATURATED=0");
    }

    @Test
    @DisplayName("예산 안에서 끝난 회차는 소진 신호를 올리지 않는다 — 거짓 신호가 되면 지표가 무의미해진다")
    void roundWithinBudget_doesNotSignalExhaustion() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress()).willReturn(List.of(
                new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));

        service.reconcile();

        assertThat(liveMetrics.acted).contains("ROUND_BUDGET_EXHAUSTED=0");
    }

    private void givenCandidates(UUID... ids) {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findStaleLiveRoomIds(any(Instant.class), anyInt())).willReturn(List.of(ids));
    }

    @Test
    @DisplayName("활성 egress 가 없는 오래된 Live 방은 종료한다")
    void staleWithoutEgress_isEnded() {
        givenCandidates(roomId);
        // 조회 자체가 살아 있다는 증거로 무관한 방의 활성 egress 를 하나 둔다 — 전체가 비면 오설정 가드에 걸린다.
        given(liveMediaManager.listAllEgress()).willReturn(List.of(new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(roomId));
    
        // 잡 이름과 갈래를 함께 못박는다 — 세 잡이 같은 모양이라 상수만 잘못 넣어도 통과한다.
        org.assertj.core.api.Assertions.assertThat(liveMetrics.acted).contains("ENDED=1");
    }

    @Test
    @DisplayName("회차 스냅샷 뒤 재연결해 활성 egress 가 생긴 방은 종료하지 않는다")
    void egressStartedAfterRoundSnapshot_isKept() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        given(liveMediaManager.listRoomEgress(roomId))
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("전역 스냅샷에는 활성인데 방별 egress가 비면 불일치로 보고 종료를 미룬다")
    void activeInRoundSnapshotButMissingPerRoom_isKeptForNextRound() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
        assertThat(liveMetrics.acted)
                .contains("SKIPPED_EGRESS_SNAPSHOT_MISMATCH=1", "ENDED=0");
    }

    @Test
    @DisplayName("활성 egress 가 있으면 오래됐어도 종료하지 않는다 — 정상적으로 긴 방송이다")
    void staleWithActiveEgress_isKept() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, STARTED)));
        given(liveMediaManager.listRoomEgress(roomId))
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    /**
     * 이 잡의 판정은 <b>만지기 직전, 방마다</b> 한다. 회차 시작 전역 스냅샷은 예산만큼(최대 20분)
     * 낡아, 그 사이 OBS 가 재접속한 방을 "송출 없음" 으로 보여준다 — 그걸로 끊으면 살아 있는 방송이
     * 죽고 15분 뒤 고아 정리가 그 방의 실제 송출 ingress 까지 지운다.
     */
    @Test
    @DisplayName("회차 스냅샷에 없던 방이라도 만지기 직전 송출 중이면 종료하지 않는다")
    void roomThatResumedAfterTheSnapshot_isSpared() {
        givenCandidates(roomId);
        // T0 스냅샷: 이 방은 송출이 없다(무관한 방 하나가 오설정 가드를 통과시킨다).
        given(liveMediaManager.listAllEgress()).willReturn(List.of(
                new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        // 그런데 실제로 만지려는 순간에는 재접속해 송출 중이다.
        given(liveMediaManager.listRoomEgress(roomId))
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
        assertThat(liveMetrics.acted).contains("SPARED=1");
    }

    @Test
    @DisplayName("방별 재확인이 실패하면 종료하지 않고 넘긴다 — 모르는 건 끊지 않는다")
    void perRoomRecheckFailure_doesNotEnd() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress()).willReturn(List.of(
                new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        given(liveMediaManager.listRoomEgress(roomId)).willThrow(new LiveMediaException("조회 실패"));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
        // 루틴 스킵("이미 판매자가 종료한 방")과 섞지 않는다 — 그쪽은 늘 0 이 아니라,
        // 섞으면 방별 호출만 지속 실패해 종료가 0건인 상태가 평범해 보인다.
        assertThat(liveMetrics.acted).contains("SKIPPED_EGRESS_LOOKUP_FAILED=1");
        assertThat(liveMetrics.acted).contains("SKIPPED=0");
    }

    @Test
    @DisplayName("egress 가 이미 멈췄으면 송출이 끝난 것으로 보고 종료한다")
    void inactiveEgress_countsAsStale() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), false, STARTED), new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        given(liveMediaManager.listRoomEgress(roomId))
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), false, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(roomId));
    }

    @Test
    @DisplayName("다른 방의 egress 는 이 방의 생존 근거가 되지 않는다")
    void otherRoomsEgress_doesNotProtect() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", UUID.randomUUID().toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(roomId));
    }

    @Test
    @DisplayName("후보가 없으면 LiveKit 을 조회하지도 않는다")
    void noCandidates_skipsLiveKit() {
        givenCandidates();

        service.reconcile();

        then(liveMediaManager).should(never()).listAllEgress();
        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("후보가 있는데 활성 egress 가 0건이면 회차를 접는다 — 200+빈 목록 오설정이 전 방송을 끊는다")
    void emptyEgressList_abortsRound() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress()).willReturn(List.of());

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("비활성 egress 만 있어도 회차를 접는다 — 활성 0건은 조회가 살아 있다는 증거가 되지 못한다")
    void onlyInactiveEgress_abortsRound() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", UUID.randomUUID().toString(), false, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("egress 조회가 실패하면 아무 방도 종료하지 않는다 — 빈 목록이면 전 방송을 끊게 된다")
    void egressLookupFailure_endsNothing() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress()).willThrow(new LiveMediaException("조회 실패"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(LiveMediaException.class);

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("한 방이 이미 종료됐어도(경합) 나머지 방 처리는 계속한다")
    void alreadyEndedRoom_isSkippedAndLoopContinues() {
        UUID other = UUID.randomUUID();
        givenCandidates(roomId, other);
        given(liveMediaManager.listAllEgress()).willReturn(List.of(new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        willThrow(new InvalidLiveStateException(roomId.toString()))
                .given(endStaleLiveUseCase).endStale(new EndStaleLiveCommand(roomId));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(other));
    }

    @Test
    @DisplayName("한 방의 egress 조회가 실패해도 그 방만 미루고 나머지 후보를 계속 처리한다")
    void roomEgressLookupFailure_skipsRoomAndContinues() {
        UUID other = UUID.randomUUID();
        givenCandidates(roomId, other);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        given(liveMediaManager.listRoomEgress(roomId)).willThrow(new LiveMediaException("조회 실패"));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(new EndStaleLiveCommand(roomId));
        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(other));
        assertThat(liveMetrics.acted)
                .contains("SKIPPED_EGRESS_LOOKUP_FAILED=1", "SKIPPED=0", "ENDED=1");
    }

    @Test
    @DisplayName("방 종료 중 LiveMediaException은 조회 실패 스킵으로 숨기지 않고 회차 실패로 올린다")
    void endFailure_isNotCountedAsEgressLookupFailure() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));
        willThrow(new LiveMediaException("종료 실패"))
                .given(endStaleLiveUseCase).endStale(new EndStaleLiveCommand(roomId));

        assertThatThrownBy(service::reconcile).isInstanceOf(LiveMediaException.class);

        assertThat(liveMetrics.acted)
                .contains("SKIPPED_EGRESS_LOOKUP_FAILED=0", "SKIPPED=0", "ENDED=0");
        assertThat(liveMetrics.failedRounds).containsExactly(ReconcileJob.END_STALE_LIVE);
    }

    @Test
    @DisplayName("LiveKit 방 이름이 roomId 형식이 아니어도 회차가 죽지 않는다")
    void nonUuidRoomName_isIgnored() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", "not-a-uuid", true, STARTED), new EgressSummary("eg-DECOY", UUID.randomUUID().toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(roomId));
    }

    @Test
    @DisplayName("클러스터 전체 활성 egress 0 으로 회차를 접으면 중단 사유를 지표로 남긴다 — 로그만으로는 평온한 회차와 구분되지 않는다")
    void abortedRound_isRecordedWithReason() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress()).willReturn(List.of());

        service.reconcile();

        assertThat(liveMetrics.abortedRounds)
                .containsExactly(ReconcileAbortReason.NO_ACTIVE_EGRESS_CLUSTER_WIDE);
        assertThat(liveMetrics.completedRounds).isEmpty();
    }

    @Test
    @DisplayName("후보가 없어도 완료 회차로 센다 — 무기록이면 스케줄러가 죽은 것과 같아 보인다")
    void emptyCandidates_stillCountsAsCompletedRound() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findStaleLiveRoomIds(any(Instant.class), anyInt())).willReturn(List.of());

        service.reconcile();

        assertThat(liveMetrics.completedRounds).containsExactly(ReconcileJob.END_STALE_LIVE);
    }

    @Test
    @DisplayName("예외로 죽은 회차는 failed 로 센다 — 없으면 매 회차 깨지는 잡과 스케줄러가 안 도는 상황이 지표상 같아진다")
    void failedRound_isCountedAndRethrown() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findStaleLiveRoomIds(any(Instant.class), anyInt()))
                .willThrow(new IllegalStateException("DB 장애"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(IllegalStateException.class);

        assertThat(liveMetrics.failedRounds).containsExactly(ReconcileJob.END_STALE_LIVE);
        assertThat(liveMetrics.completedRounds).isEmpty();
        assertThat(liveMetrics.abortedRounds).isEmpty();
    }
}
