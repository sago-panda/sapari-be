package com.sapari.live.infrastructure.recentview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.QueryTimeoutException;

import com.sapari.live.application.port.RecentViewDropReason;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.domain.model.RecentView;
import com.sapari.live.domain.repository.RecentViewRepository;

@DisplayName("최근 본 기록기 — 입장 응답과 떼고, 버린 기록은 센다")
class AsyncRecentViewRecorderTest {

    private static final Executor INLINE = Runnable::run;
    private static final Executor FULL = task -> {
        throw new TaskRejectedException("queue full");
    };

    private final RecentViewRepository repository = mock(RecentViewRepository.class);
    private final RecordingLiveMetrics metrics = new RecordingLiveMetrics();
    private final AtomicLong clock = new AtomicLong(0);

    private final UUID userId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-11-10T09:00:00Z");

    private AsyncRecentViewRecorder recorder(Executor executor) {
        return new AsyncRecentViewRecorder(executor, repository::upsert, metrics, clock::get);
    }

    @Test
    @DisplayName("실행기에서 (회원, 방, 시각) 을 upsert 한다")
    void writesOnExecutor() {
        recorder(INLINE).record(userId, roomId, at);

        verify(repository).upsert(new RecentView(userId, roomId, at));
        assertThat(metrics.recentViewDrops).isEmpty();
    }

    @Test
    @DisplayName("대기열이 가득하면 버리고 REJECTED 로 센다 — 호출자에게 던지지 않는다")
    void rejectedIsCountedNotThrown() {
        assertThatCode(() -> recorder(FULL).record(userId, roomId, at)).doesNotThrowAnyException();

        verifyNoInteractions(repository);
        assertThat(metrics.recentViewDrops).containsExactly(RecentViewDropReason.REJECTED);
    }

    @Test
    @DisplayName("쓰기가 실패하면 버리고 FAILED 로 센다 — 워커 밖으로 던지지 않는다")
    void writeFailureIsCountedNotThrown() {
        willThrow(new QueryTimeoutException("db slow")).given(repository).upsert(any());

        assertThatCode(() -> recorder(INLINE).record(userId, roomId, at)).doesNotThrowAnyException();

        assertThat(metrics.recentViewDrops).containsExactly(RecentViewDropReason.FAILED);
    }

    @Test
    @DisplayName("연속 실패는 모두 세되 WARN 은 10초 창마다 한 줄이고 회원·방 식별자를 싣지 않는다")
    void everyFailureCountedButLogThrottled() {
        willThrow(new QueryTimeoutException("db down")).given(repository).upsert(any());
        AsyncRecentViewRecorder recorder = recorder(INLINE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(AsyncRecentViewRecorder.class);
        logger.addAppender(appender);
        try {
            recorder.record(userId, roomId, at);
            clock.addAndGet(1_000_000_000L);       // 1s — 같은 창
            recorder.record(userId, roomId, at);
            clock.addAndGet(10_000_000_000L);      // 11s — 다음 창
            recorder.record(userId, roomId, at);

            assertThat(metrics.recentViewDrops).hasSize(3).containsOnly(RecentViewDropReason.FAILED);
            assertThat(appender.list).hasSize(2).allSatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage())
                        .contains("QueryTimeoutException")
                        .doesNotContain(userId.toString(), roomId.toString());
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
