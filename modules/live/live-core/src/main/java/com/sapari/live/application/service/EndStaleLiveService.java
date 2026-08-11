package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveEventPublisher;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.EndStaleLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EndStaleLiveUseCase;

/**
 * 방치된 Live 방 종료 — 방 1건의 전이·미디어 정리·이벤트 발행.
 *
 * <p>{@code ExpireOrphanLiveService} 와 달리 <b>{@code RoomEnded} 를 발행한다</b> — 이 방은 Live 였으므로
 * chat 세션이 열려 있고, 안 닫으면 시청자가 죽은 방에 남는다. 발행을 빼지 말 것.
 *
 * <p>전이가 미디어 정리보다 먼저다 — {@code endLive()} 가 상태 가드를 겸한다. 조회~잠금 사이에 판매자가
 * 직접 종료했다면 여기서 {@code InvalidLiveStateException} 이 나고, LiveKit 에는 아무것도 나가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndStaleLiveService implements EndStaleLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final LiveEventPublisher liveEventPublisher;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public void endStale(EndStaleLiveCommand command) {
        LiveRoom room = liveRoomRepository.findByIdForUpdate(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        if (!room.canEndLive()) {
            throw new InvalidLiveStateException(command.roomId().toString());
        }

        Instant endedAt = timeProvider.now();
        LiveRoom endedRoom = room.endLive(endedAt);

        liveRoomRepository.save(endedRoom);

        // 등록 순서 = 실행 순서. 미디어를 먼저 걷고 나서 chat 에 알린다.
        PostCommitMediaCleanup.register(liveMediaManager, room);
        registerRoomEndedPublish(command.roomId(), endedAt);
        log.info("방치된 Live 방 종료. roomId={}", command.roomId());
    }

    /** 커밋 이후에만 발행 — 롤백 시 오발행(멀쩡한 방 세션을 chat 이 닫는 것)을 막는다. */
    private void registerRoomEndedPublish(UUID roomId, Instant endedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화 비활성 — RoomEnded 발행 생략. roomId={}", roomId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                liveEventPublisher.publishRoomEnded(roomId, endedAt);
            }
        });
    }
}
