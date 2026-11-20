package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.live.application.port.ListCacheWrite;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;

@ExtendWith(MockitoExtension.class)
class LiveListCacheSyncTest {

    @Mock
    private LiveRoomCacheRepository cacheRepository;

    private final RecordingLiveMetrics metrics = new RecordingLiveMetrics();

    private LiveListCacheSync sync;

    @BeforeEach
    void setUp() {
        sync = new LiveListCacheSync(cacheRepository, metrics);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("등록은 커밋 후에 한다 — 커밋 전에는 목록을 건드리지 않는다")
    void register_waitsForCommit() {
        LiveRoom room = liveRoom();

        sync.registerAfterCommit(room);
        verify(cacheRepository, never()).register(any());

        commit();
        ArgumentCaptor<LiveRoomCache> captor = ArgumentCaptor.forClass(LiveRoomCache.class);
        verify(cacheRepository).register(captor.capture());
        assertThat(captor.getValue().roomId()).isEqualTo(room.id());
        assertThat(captor.getValue().title()).isEqualTo("방송");
    }

    @Test
    @DisplayName("롤백되면 목록에 넣지 않는다 — 유령 방송 금지")
    void register_skippedOnRollback() {
        sync.registerAfterCommit(liveRoom());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cacheRepository, never()).register(any());
    }

    @Test
    @DisplayName("저장소 장애는 삼키고 센다 — 커밋된 시작을 실패로 보이게 하지 않는다")
    void register_storeFailureIsCountedNotThrown() {
        willThrow(new RedisConnectionFailureException("down")).given(cacheRepository).register(any());
        sync.registerAfterCommit(liveRoom());

        commit();

        assertThat(metrics.listCacheWriteFailures).containsExactly(ListCacheWrite.REGISTER);
    }

    @Test
    @DisplayName("저장소 장애가 아닌 예외는 결함이므로 올린다")
    void register_otherFailuresPropagate() {
        willThrow(new IllegalStateException("bug")).given(cacheRepository).register(any());
        sync.registerAfterCommit(liveRoom());

        assertThatThrownBy(this::commit).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.listCacheWriteFailures).isEmpty();
    }

    @Test
    @DisplayName("방송 중이 아닌 방을 등록하려 하면 커밋 전에 드러난다")
    void register_rejectsNonLiveImmediately() {
        LiveRoom ready = liveRoom().toBuilder().status(new LiveStatus.Ready(Instant.now())).build();

        assertThatThrownBy(() -> sync.registerAfterCommit(ready)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("제거도 커밋 후에 하고, 장애는 REMOVE 로 센다")
    void remove_waitsForCommitAndCountsFailure() {
        UUID roomId = UUID.randomUUID();
        willThrow(new RedisConnectionFailureException("down")).given(cacheRepository).remove(roomId);

        sync.removeAfterCommit(roomId);
        verify(cacheRepository, never()).remove(any());

        commit();
        verify(cacheRepository).remove(roomId);
        assertThat(metrics.listCacheWriteFailures).containsExactly(ListCacheWrite.REMOVE);
    }

    @Test
    @DisplayName("트랜잭션 밖 호출이면 즉시 반영한다")
    void appliesImmediately_withoutTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        UUID roomId = UUID.randomUUID();

        sync.removeAfterCommit(roomId);

        verify(cacheRepository).remove(roomId);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private static LiveRoom liveRoom() {
        Instant started = Instant.parse("2026-11-06T05:00:00Z");
        return LiveRoom.builder()
                .id(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .title("방송")
                .sellerNickname("판매자")
                .streamType(new LiveStreamType.WebRtc())
                .status(new LiveStatus.Live(started, "sfu-1", "egress-1", "https://cdn/index.m3u8"))
                .scheduledAt(started)
                .build();
    }
}
