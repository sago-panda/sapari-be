package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.live.application.port.ListCacheWrite;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;

/**
 * 방송 전이를 공개 목록 캐시에 <b>커밋 후</b> 반영한다. Live 로 들어가는 전이는 등록, 나오는 전이는 제거.
 *
 * <p>커밋 후인 이유: 롤백된 시작이 목록에 유령 방송으로 남지 않게 하고, Redis 왕복을 행 잠금 밖으로 뺀다
 * ({@link PostCommitMediaCleanup} 과 같은 판단).
 *
 * <p>[의도된 swallow] 저장소 실패({@link DataAccessException})는 지표만 남기고 다시 던지지 않는다.
 * 커밋 훅의 예외는 커밋 호출자에게 전파되므로, 던지면 <b>이미 커밋된 시작·종료가 판매자에게 실패로 보이고</b>
 * 재시도는 상태 가드(LIVE-003·009)에 걸린다. 어긋난 목록은 {@code RefreshLiveListService} 가 한 회차(기본 15초) 안에
 * 원본(DB)으로 되돌린다. 그 밖의 예외는 결함이므로 그대로 올린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveListCacheSync {

    private final LiveRoomCacheRepository liveRoomCacheRepository;
    private final LiveMetrics liveMetrics;

    /** Live 로 저장한 직후 부른다. 항목은 지금 만든다 — 상태 결함은 커밋 전에 드러난다. */
    public void registerAfterCommit(LiveRoom liveRoom) {
        LiveRoomCache entry = LiveRoomCache.fromLive(liveRoom);
        afterCommit(ListCacheWrite.REGISTER, liveRoom.id(), () -> liveRoomCacheRepository.register(entry));
    }

    /** Live 에서 벗어나는 전이를 저장한 직후 부른다. */
    public void removeAfterCommit(UUID roomId) {
        afterCommit(ListCacheWrite.REMOVE, roomId, () -> liveRoomCacheRepository.remove(roomId));
    }

    /** 트랜잭션 밖 호출이면 즉시 반영한다 — 커밋할 것이 없으니 기다릴 것도 없다. */
    private void afterCommit(ListCacheWrite write, UUID roomId, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            apply(write, roomId, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                apply(write, roomId, action);
            }
        });
    }

    private void apply(ListCacheWrite write, UUID roomId, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException e) {
            log.warn("공개 목록 반영 실패 — 재조정이 복구. write={}, roomId={}", write, roomId, e);
            liveMetrics.listCacheWriteFailed(write);
        }
    }
}
