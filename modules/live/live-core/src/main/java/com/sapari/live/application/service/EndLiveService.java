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

    @Override
    @Transactional
    public void end(EndLiveCommand command){
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 외부 호출 전 상태 사전 검증
        if (!room.canEndLive()) {
            throw new InvalidLiveStateException(room.id().toString());
        }

        // 정리 순서 고정: egress 중단 → ingress 삭제 → 방 삭제
        // (ingress 가 남아 있으면 OBS 자동 재접속이 닫힌 SFU 방을 재생성한다 — 좀비 방)
        boolean hasMediaSession = room.streamInfo() != null;
        if (hasMediaSession) {
            liveMediaManager.stopHlsEgress(command.roomId(), room.egressId());
        }
        if (room.isRtmp()) {
            liveMediaManager.deleteIngress(command.roomId());
        }
        if (hasMediaSession) {
            liveMediaManager.closeRoom(room.sfuRoomId());
        }

        Instant endedAt = timeProvider.now();
        LiveRoom endedRoom = room.endLive(endedAt);

        liveRoomRepository.save(endedRoom);

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
