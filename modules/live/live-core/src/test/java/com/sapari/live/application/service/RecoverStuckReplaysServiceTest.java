package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.domain.repository.LiveRoomRepository;

class RecoverStuckReplaysServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private final LiveRoomRepository repository = mock(LiveRoomRepository.class);
    private final TimeProvider timeProvider = mock(TimeProvider.class);
    private final RecordingLiveMetrics metrics = new RecordingLiveMetrics();
    private final RecoverStuckReplaysService service = new RecoverStuckReplaysService(repository, timeProvider, metrics);

    @Test
    @DisplayName("종료 15분 전을 기준으로 올리고, 올린 수를 REPLAY_RECOVERED 로 센다")
    void recoversWithGraceAndRecordsCount() {
        given(timeProvider.now()).willReturn(NOW);
        given(repository.recoverStuckReplays(NOW.minusSeconds(900))).willReturn(2);

        assertThat(service.recover()).isEqualTo(2);
        assertThat(metrics.acted).containsExactly("REPLAY_RECOVERY_FAILED=0", "REPLAY_RECOVERED=2");
    }

    @Test
    @DisplayName("0건도 센다 — 시계열이 끊기면 '안 돌았다' 와 '유실 없음' 이 구분되지 않는다")
    void recordsZero() {
        given(timeProvider.now()).willReturn(NOW);

        assertThat(service.recover()).isZero();
        assertThat(metrics.acted).containsExactly("REPLAY_RECOVERY_FAILED=0", "REPLAY_RECOVERED=0");
    }

    @Test
    @DisplayName("실패는 세고 다시 던진다 — 스케줄러가 로그만 남기므로 여기서 세지 않으면 '복구 0건' 과 구분되지 않는다")
    void failureIsCountedAndRethrown() {
        given(timeProvider.now()).willReturn(NOW);
        given(repository.recoverStuckReplays(org.mockito.ArgumentMatchers.any()))
                .willThrow(new IllegalStateException("DB 장애"));

        org.assertj.core.api.Assertions.assertThatThrownBy(service::recover).hasMessage("DB 장애");
        assertThat(metrics.acted).containsExactly("REPLAY_RECOVERY_FAILED=1");
    }
}
