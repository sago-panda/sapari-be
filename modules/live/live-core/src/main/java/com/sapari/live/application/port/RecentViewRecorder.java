package com.sapari.live.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * 최근 본 기록을 남긴다. 입장 응답을 기록에서 떼기 위한 경계다(SPR-172 FR-005).
 *
 * <p><b>구현은 절대 던지지 않고, 호출자를 쓰기 완료까지 기다리게 하지 않는다.</b> 기록은 편의 기능이고 입장은 시청
 * 자체라서, 기록 때문에 입장을 실패시키거나 늦추는 쪽이 더 큰 손해다. 버린 기록은 숨기지 않고
 * {@link LiveMetrics#recentViewDropped} 로 센다.
 */
public interface RecentViewRecorder {

    void record(UUID userId, UUID roomId, Instant viewedAt);
}
