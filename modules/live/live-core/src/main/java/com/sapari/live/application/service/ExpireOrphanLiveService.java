package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ExpireOrphanLiveUseCase;

/**
 * 고아 라이브 방 정리 — LiveKit 미디어 리소스를 회수하고 방을 만료 종료시킨다.
 *
 * <p>{@code EndLiveService}와 달리 전이가 미디어 정리보다 먼저다 — {@code expire()}가 상태 가드를 겸한다.
 * 순서를 뒤집으면 방금 Live 가 된 방의 egress 를 끊게 되니 되돌리지 말 것.
 *
 * <p>{@code RoomEnded}는 발행하지 않는다 — Live 였던 적이 없어 닫을 chat 세션이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpireOrphanLiveService implements ExpireOrphanLiveUseCase {

    private final TimeProvider timeProvider;
    private final LiveMetrics liveMetrics;
    private final LiveMediaManager liveMediaManager;
    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional
    public void expire(ExpireOrphanLiveCommand command){
        LiveRoom room = liveRoomRepository.findByIdForUpdate(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));
        LiveRoom expiredRoom = room.expire(timeProvider.now());

        liveRoomRepository.save(expiredRoom);
        liveMetrics.roomTransitioned(room.status(), expiredRoom.status());
        PostCommitMediaCleanup.register(liveMediaManager, room);
        log.info("고아 라이브 정리. roomId: {}", expiredRoom.id());
    }
}
