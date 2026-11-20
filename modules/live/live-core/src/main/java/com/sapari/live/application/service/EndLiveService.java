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
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EndLiveUseCase;

@Slf4j
@Service
@RequiredArgsConstructor
public class EndLiveService implements EndLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final LiveEventPublisher liveEventPublisher;
    private final TimeProvider timeProvider;
    private final LiveMetrics liveMetrics;

    @Override
    @Transactional
    public void end(EndLiveCommand command){
        LiveRoom room = liveRoomRepository.findByIdAndSellerIdForUpdate(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 외부 호출 전 상태 사전 검증
        if (!room.canEndLive()) {
            throw new InvalidLiveStateException(room.id().toString());
        }

        Instant endedAt = timeProvider.now();
        LiveRoom endedRoom = room.endLive(endedAt);

        liveRoomRepository.save(endedRoom);
        liveMetrics.roomTransitioned(room.status(), endedRoom.status());

        // save 뒤에 등록한다 — 트랜잭션 밖 호출이면 register 가 즉시 정리해 버리므로, 앞에 두면
        // save 가 실패했을 때 멀쩡한 방의 미디어만 파괴된다(다른 두 호출자와 순서도 맞춘다).
        PostCommitMediaCleanup.register(liveMediaManager, room);

        // 종료 커밋 이후에만 RoomEnded 발행 — 롤백 시 오발행(멀쩡한 방 세션을 chat이 닫는 것)을 막는다.
        registerRoomEndedPublish(command.roomId(), endedAt);
    }

    private void registerRoomEndedPublish(UUID roomId, Instant endedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖 호출(회귀 방지) — 커밋 보장이 없으면 발행하지 않는다.
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
