package com.sapari.live.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.live.application.port.LiveMediaManager;

/**
 * 방송 시작 시 시작해둔 HLS egress 를, 트랜잭션이 롤백으로 끝나면 되돌리는 보상 훅.
 * 본문 예외뿐 아니라 커밋 시점 flush 실패(write-behind INSERT 실패)까지 커버한다.
 *
 * <p>WebRTC 시작({@code StartLiveService})과 RTMP go-live({@code GoLiveByRtmpService})가 공유한다 —
 * 롤백 상태 판정·중단 정책이 민감해 단일 출처로 둔다({@code afterCompletion}은 트랜잭션/커넥션이 끝난 뒤
 * 실행되므로 미디어 포트 호출만 허용하고 repository/EntityManager 접근은 금지).
 */
final class EgressRollbackCompensation {

    private static final Logger log = LoggerFactory.getLogger(EgressRollbackCompensation.class);

    private EgressRollbackCompensation() {
    }

    /** egress 시작 직후 호출 — 트랜잭션이 롤백되면 해당 egress 를 best-effort 로 중단한다. */
    static void register(LiveMediaManager mediaManager, UUID roomId, String egressId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    safeStopEgress(mediaManager, roomId, egressId);
                }
                // STATUS_UNKNOWN은 보상하지 않는다 — 커밋됐을 수도 있는 방송의 egress를
                // 끊으면 안 되므로, 이 케이스는 reconciliation(배치)의 몫으로 남긴다.
            }
        });
    }

    private static void safeStopEgress(LiveMediaManager mediaManager, UUID roomId, String egressId) {
        try {
            mediaManager.stopHlsEgress(roomId);
            log.warn("방송 시작 롤백 → egress 보상 중단 완료. roomId={}, egressId={}", roomId, egressId);
        } catch (RuntimeException e) {
            // [의도된 swallow] afterCompletion은 트랜잭션 종료 후 실행되므로 여기서 재던져도
            // 호출자에게 전파되지 않고, 원본 롤백 원인만 가린다. 고아 egress는 로그/알람으로 추적.
            log.error("egress 보상 중단 실패 — 고아 egress 발생. roomId={}, egressId={}", roomId, egressId, e);
        }
    }
}
