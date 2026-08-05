package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private ReconcileStaleLiveService service;

    private UUID roomId;

    @BeforeEach
    void setup() {
        service = new ReconcileStaleLiveService(
                liveRoomRepository, liveMediaManager, endStaleLiveUseCase,
                new StaleLiveReconcilePolicy(Duration.ofMinutes(60), 100), timeProvider);
        roomId = UUID.randomUUID();
    }

    private void givenCandidates(UUID... ids) {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findStaleLiveRoomIds(any(Instant.class), anyInt())).willReturn(List.of(ids));
    }

    @Test
    @DisplayName("활성 egress 가 없는 오래된 Live 방은 종료한다")
    void staleWithoutEgress_isEnded() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress()).willReturn(List.of());

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(roomId));
    }

    @Test
    @DisplayName("활성 egress 가 있으면 오래됐어도 종료하지 않는다 — 정상적으로 긴 방송이다")
    void staleWithActiveEgress_isKept() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(never()).endStale(any(EndStaleLiveCommand.class));
    }

    @Test
    @DisplayName("egress 가 이미 멈췄으면 송출이 끝난 것으로 보고 종료한다")
    void inactiveEgress_countsAsStale() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
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
        given(liveMediaManager.listAllEgress()).willReturn(List.of());
        willThrow(new InvalidLiveStateException(roomId.toString()))
                .given(endStaleLiveUseCase).endStale(new EndStaleLiveCommand(roomId));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(other));
    }

    @Test
    @DisplayName("LiveKit 방 이름이 roomId 형식이 아니어도 회차가 죽지 않는다")
    void nonUuidRoomName_isIgnored() {
        givenCandidates(roomId);
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", "not-a-uuid", true, STARTED)));

        service.reconcile();

        then(endStaleLiveUseCase).should(times(1)).endStale(new EndStaleLiveCommand(roomId));
    }
}
