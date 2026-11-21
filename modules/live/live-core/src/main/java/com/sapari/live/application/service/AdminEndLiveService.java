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
import com.sapari.live.command.AdminEndLiveCommand;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveAuditLogRepository;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.AdminEndLiveUseCase;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminEndLiveService implements AdminEndLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveAuditLogRepository liveAuditLogRepository;
    private final LiveMediaManager liveMediaManager;
    private final LiveEventPublisher liveEventPublisher;
    private final TimeProvider timeProvider;
    private final LiveMetrics liveMetrics;
    private final LiveListCacheSync liveListCacheSync;

    @Override
    @Transactional
    public void end(AdminEndLiveCommand command) {
        LiveRoom room = liveRoomRepository.findByIdForUpdate(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        Instant endedAt = timeProvider.now();
        // 결과는 Ended 가 아니라 Cancelled 다 — 다시보기가 생기지 않는다. 채팅 세션은 열려 있으므로
        // 판매자 예약 취소(CancelLiveService)와 달리 아래에서 RoomEnded 를 발행한다.
        LiveRoom endedRoom = room.cancelByAdmin(endedAt);

        liveRoomRepository.save(endedRoom);
        liveMetrics.roomTransitioned(room.status(), endedRoom.status());
        liveAuditLogRepository.appendAdminEnd(command.adminId(), command.roomId(), command.reason(), endedAt);

        // 등록 순서 = 실행 순서 — EndLiveService 와 같은 이유로 미디어 → RoomEnded → 목록.
        PostCommitMediaCleanup.register(liveMediaManager, room);
        registerRoomEndedPublish(command.roomId(), endedAt);
        liveListCacheSync.removeAfterCommit(endedRoom.id());
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
