package com.sapari.live.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.domain.model.LiveRoom;

/**
 * 방 종료 후 LiveKit 리소스 회수를 <b>커밋 이후</b>로 미룬다. 종료 계열 세 서비스
 * ({@code EndLiveService}·{@code ExpireOrphanLiveService}·{@code EndStaleLiveService})가 공유한다.
 *
 * <p>미루는 이유: 정리 3종은 방과 함께 커밋돼야 할 것이 없는데(시작 측의 {@code egressId} 와 다르다),
 * 트랜잭션 안에서 부르면 LiveKit 왕복(호출당 15s, 최대 3건)만큼 행 잠금을 쥐고 있게 된다. 그동안 같은
 * 방에 대한 다른 전이(webhook go-live, 정리 배치)가 통째로 대기한다.
 *
 * <p>대가는 커밋~정리 사이 크래시로 리소스가 남는 것인데, 그건 고아 미디어 정리 잡이 회수한다.
 */
final class PostCommitMediaCleanup {

    private static final Logger log = LoggerFactory.getLogger(PostCommitMediaCleanup.class);

    private PostCommitMediaCleanup() {
    }

    /**
     * 커밋 후 정리를 예약한다. 트랜잭션 밖 호출이면 즉시 정리한다 — {@code RoomEnded} 발행과 달리
     * 생략하면 egress 과금이 계속되므로 "안 하고 넘어가기"가 안전한 선택이 아니다.
     */
    static void register(LiveMediaManager mediaManager, LiveRoom room) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup(mediaManager, room);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup(mediaManager, room);
            }
        });
    }

    /**
     * 정리 순서 고정: egress 중단 → ingress 삭제 → 방 삭제.
     * ingress 가 남아 있으면 OBS 자동 재접속이 닫힌 SFU 방을 재생성한다(좀비 방) — 순서를 바꾸지 말 것.
     *
     * <p>[의도된 swallow] 여기까지 왔으면 방은 이미 Ended 로 커밋됐다. 예외를 올리면 호출자는 종료가
     * 실패한 줄 알고 재시도하는데, 그때는 상태 가드에 걸려 오히려 에러만 본다. LiveKit 에 남은 리소스는
     * 사용자가 알 일이 아니라 고아 미디어 정리 잡의 몫이다.
     */
    private static void cleanup(LiveMediaManager mediaManager, LiveRoom room) {
        try {
            // egress 중단은 DB 가 egress 를 모르더라도 부른다 — roomId 로 LiveKit 에 직접 물어 일괄
            // 중단하므로 DB 가 놓친 잔여 egress(화질별 다건·시작 중 크래시분)도 함께 걷힌다.
            mediaManager.stopHlsEgress(room.id());
            if (room.isRtmp()) {
                mediaManager.deleteIngress(room.id());
            }
            // createRoom 실패로 SFU 방이 배정되지 않은 방은 stream 접근자가 NPE — 그 방만 건너뛴다.
            if (room.streamInfo() != null) {
                mediaManager.closeRoom(room.sfuRoomId());
            }
        } catch (RuntimeException e) {
            log.error("방 종료 후 미디어 정리 실패 — 고아 리소스 발생, 정리 배치가 회수. roomId={}", room.id(), e);
        }
    }
}
