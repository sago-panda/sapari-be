package com.sapari.live.domain.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sapari.live.domain.model.LiveRoomCache;

/**
 * 공개 방송 중 목록 캐시. 원본은 DB 의 {@code status = LIVE} 방이고, 이 캐시는 그 파생이다.
 */
public interface LiveRoomCacheRepository {

    List<LiveRoomCache> findTopByViewers(int limit);

    /**
     * 방송 중인 방을 목록에 넣는다. <b>멱등이다</b> — 같은 방을 다시 넣어도 이미 쌓인 시청자 수는
     * 0 으로 돌아가지 않는다(중복 웹훅·재시도).
     */
    void register(LiveRoomCache room);

    /** 방을 목록에서 뺀다. 없는 방이어도 조용히 끝난다. */
    void remove(UUID roomId);

    /**
     * 목록을 원본에 맞춘다.
     *
     * <ul>
     *   <li>{@code live} 에 없는 구성원은 지운다.</li>
     *   <li>{@code live} 의 방은 표시 필드를 다시 쓴다(없던 방이면 넣는다).</li>
     *   <li>{@code viewers} 에 값이 있는 방은 시청자 수를 그 값으로 덮고, 없는 방은 <b>직전 값을 유지</b>한다
     *       (처음 들어온 방은 0).</li>
     * </ul>
     *
     * <p>{@code pruneStale=false} 면 {@code live} 에 없는 구성원을 지우지 않는다 — 원본을 상한까지만 읽은 회차에서
     * 읽지 못한 방송을 "끝난 방" 으로 오인해 지우지 않기 위해서다.
     */
    void reconcile(List<LiveRoomCache> live, Map<UUID, Long> viewers, boolean pruneStale);
}
