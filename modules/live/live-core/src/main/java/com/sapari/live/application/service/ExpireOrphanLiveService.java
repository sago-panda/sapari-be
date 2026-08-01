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

        // Ready 방은 sfuRoomId만 있는 게 정상 — 미디어 세션 유무로 가른다.
        boolean hasMediaSession = room.streamInfo() != null;

        // egress 중단까지 부르는 건 시작 중 크래시로 egress만 남은 방 때문이다.
        // 정리 순서 고정: egress 중단 → ingress 삭제 → 방 삭제
        // (ingress가 남아 있으면 OBS 자동 재접속이 닫힌 SFU 방을 재생성한다 — 좀비 방)
        if (hasMediaSession) {
            liveMediaManager.stopHlsEgress(command.roomId(), room.egressId());
        }
        if (room.isRtmp()) {
            liveMediaManager.deleteIngress(command.roomId());
        }
        if (hasMediaSession) {
            liveMediaManager.closeRoom(room.sfuRoomId());
        }

        liveRoomRepository.save(expiredRoom);
        log.info("고아 라이브 정리. roomId: {}", expiredRoom.id());
    }
}
