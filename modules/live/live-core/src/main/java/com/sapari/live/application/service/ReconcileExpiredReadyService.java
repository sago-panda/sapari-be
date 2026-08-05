package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ExpiredReadyReconcilePolicy;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ExpireOrphanLiveUseCase;
import com.sapari.live.port.ReconcileExpiredReadyUseCase;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileExpiredReadyService implements ReconcileExpiredReadyUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final ExpireOrphanLiveUseCase expireOrphanLiveUseCase;
    private final ExpiredReadyReconcilePolicy policy;
    private final TimeProvider timeProvider;

    @Override
    public void reconcile(){
        int expired = 0;
        int skipped = 0;
        Instant threshold = timeProvider.now().minus(policy.threshold());
        List<UUID> roomIds = liveRoomRepository.findExpiredReadyRoomIds(threshold, policy.batchSize());
        for (UUID id : roomIds) {
            try {
                expireOrphanLiveUseCase.expire(new ExpireOrphanLiveCommand(id));
                expired++;
            }catch (InvalidLiveStateException | LiveNotFoundException e){
                log.info("Ready 만료 스킵 — 이미 처리된 방. roomId={}, 사유={}", id, e.getClass().getSimpleName());
                skipped++;
            }
        }
        log.info("고아 Ready 방 정리 완료. 후보={}, 만료={}, 스킵={}", roomIds.size(), expired, skipped);
    }
}
