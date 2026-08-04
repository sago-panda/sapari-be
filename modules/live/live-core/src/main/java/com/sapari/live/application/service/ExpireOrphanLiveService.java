package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMediaManager;
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
    private final LiveMediaManager liveMediaManager;
    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional
    public void expire(ExpireOrphanLiveCommand command){
        LiveRoom room = liveRoomRepository.findByIdForUpdate(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));
        LiveRoom expiredRoom = room.expire(timeProvider.now());

        // createRoom 실패로 SFU 방이 배정되지 않은 방은 stream 접근자가 NPE — 여기서 한 번만 가른다.
        boolean hasSfuRoom = room.streamInfo() != null;

        // 정리 순서 고정: egress 중단 → ingress 삭제 → 방 삭제
        // (ingress가 남아 있으면 OBS 자동 재접속이 닫힌 SFU 방을 재생성한다 — 좀비 방)

        // egress 중단은 DB 가 egress 를 모르더라도 부른다 — 시작 중 크래시로 egress 만 남았을 수 있고,
        // 이 호출은 roomId 로 LiveKit 에 직접 물어 일괄 중단하므로 DB 상태와 무관하게 걷힌다.
        liveMediaManager.stopHlsEgress(command.roomId());
        if (room.isRtmp()) {
            liveMediaManager.deleteIngress(command.roomId());
        }
        if (hasSfuRoom) {
            liveMediaManager.closeRoom(room.sfuRoomId());
        }

        liveRoomRepository.save(expiredRoom);
        log.info("고아 라이브 정리. roomId: {}", expiredRoom.id());
    }
}
