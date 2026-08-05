package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.exception.LiveNotFoundException;
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

    private ReconcileExpiredReadyService service;

    @BeforeEach
    void setup() {
        service = new ReconcileExpiredReadyService(
                liveRoomRepository, liveMediaManager, expireOrphanLiveUseCase, goLiveByRtmpService,
                new ExpiredReadyReconcilePolicy(THRESHOLD, 100), timeProvider);
    }

    /** 후보만 있고 송출 중인 방은 없는 기본 상태. */
    private void givenCandidates(UUID... ids) {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(ids));
        if (ids.length > 0) {
            given(liveMediaManager.listAllIngress()).willReturn(List.of());
        }
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
        given(liveMediaManager.listAllIngress())
                .willReturn(List.of(new IngressSummary("ing-1", roomId.toString(), true)));

        service.reconcile();

        then(goLiveByRtmpService).should(times(1)).goLiveByRtmp(roomId);
        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }

    @Test
    @DisplayName("ingress 는 있지만 송출 중이 아니면 그대로 만료한다")
    void idleIngress_isStillExpired() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listAllIngress())
                .willReturn(List.of(new IngressSummary("ing-1", roomId.toString(), false)));

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(roomId));
        then(goLiveByRtmpService).should(never()).goLiveByRtmp(any(UUID.class));
    }

    @Test
    @DisplayName("다른 방이 송출 중이어도 이 방의 만료를 막지 않는다")
    void otherRoomPublishing_doesNotProtect() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listAllIngress())
                .willReturn(List.of(new IngressSummary("ing-1", UUID.randomUUID().toString(), true)));

        service.reconcile();

        then(expireOrphanLiveUseCase).should(times(1)).expire(new ExpireOrphanLiveCommand(roomId));
    }

    @Test
    @DisplayName("ingress 조회가 실패하면 아무 방도 만료하지 않는다 — 빈 목록으로 처리하면 송출 중인 방까지 끊는다")
    void ingressLookupFailure_expiresNothing() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findExpiredReadyRoomIds(any(Instant.class), anyInt())).willReturn(List.of(roomId));
        given(liveMediaManager.listAllIngress()).willThrow(new LiveMediaException("조회 실패"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(LiveMediaException.class);

        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
        then(goLiveByRtmpService).should(never()).goLiveByRtmp(any(UUID.class));
    }

    @Test
    @DisplayName("후보가 없으면 LiveKit 을 조회하지도 않는다")
    void noCandidates_doesNothing() {
        givenCandidates();

        service.reconcile();

        then(liveMediaManager).should(never()).listAllIngress();
        then(expireOrphanLiveUseCase).should(never()).expire(any(ExpireOrphanLiveCommand.class));
    }
}
