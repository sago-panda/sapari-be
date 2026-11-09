package com.sapari.live.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LiveRoomCacheRedisRepository implements LiveRoomCacheRepository {

    private static final String FIELD_SELLER_ID = "sellerId";
    private static final String FIELD_SELLER_NICKNAME = "sellerNickname";
    private static final String FIELD_THUMBNAIL_URL = "thumbnailUrl";
    private static final String FIELD_HLS_URL = "hlsUrl";
    private static final String FIELD_PINNED_PRODUCT_NAME = "pinnedProductName";
    private static final String FIELD_PINNED_PRODUCT_IMAGE_URL = "pinnedProductImageUrl";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_STARTED_AT = "startedAt";

    private final StringRedisTemplate redis;

    /**
     * ZSET ranking에서 상위 limit개 roomId를 조회한 뒤,
     * 각 roomId의 Hash 데이터를 Redis Pipeline으로 일괄 조회한다.
     * Pipeline 사용으로 N+1 round trip을 2번으로 줄임.
     */
    @Override
    public List<LiveRoomCache> findTopByViewers(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(LiveRedisKeys.ranking(), 0, limit - 1L);

        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        // 1. 유효한 (roomId, score) 쌍만 정제 → 이후 단계의 인덱스 매핑 안전성 보장
        List<RankedRoom> ranked = tuples.stream()
                .map(this::toRankedRoom)
                .filter(Objects::nonNull)
                .toList();

        if (ranked.isEmpty()) {
            return List.of();
        }

        // 2. 정제된 roomId만 Pipeline으로 HGETALL 일괄 조회
        List<Object> hashResults = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (RankedRoom r : ranked) {
                    operations.opsForHash().entries(LiveRedisKeys.room(r.roomId()));
                }
                return null;
            }
        });

        // 3. ranked와 hashResults는 동일 순서/크기 보장 → 안전하게 매핑
        List<LiveRoomCache> result = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            //map이 들어올 것이지만 java는 List<Object>를 리턴해서 강제 형변환 과정에서 에러코드 발생할 수 있으므로 막기위해 붙임
            @SuppressWarnings("unchecked")
            Map<Object, Object> fields = (Map<Object, Object>) hashResults.get(i);
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            result.add(toCache(ranked.get(i).roomId(), fields, ranked.get(i).viewers()));
        }
        return result;
    }

    @Override
    public void register(LiveRoomCache room) {
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) throws DataAccessException {
                writeRoom(operations, room);
                // NX — 중복 등록(재전송 웹훅·재시도)이 이미 쌓인 시청자 수를 0 으로 되돌리지 않게 한다.
                operations.opsForZSet().addIfAbsent(LiveRedisKeys.ranking(), room.roomId().toString(), 0);
                return null;
            }
        });
    }

    @Override
    public void remove(UUID roomId) {
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForZSet().remove(LiveRedisKeys.ranking(), roomId.toString());
                operations.delete(LiveRedisKeys.room(roomId));
                return null;
            }
        });
    }

    /**
     * 구성원을 읽은 뒤(1회) 지우기·쓰기를 pipeline 한 번에 보낸다. 두 단계 사이에 들어온 등록·제거와 겹치면
     * 한 회차만큼 어긋날 수 있다 — 다음 회차가 같은 원본으로 다시 맞춘다(plan Complexity Tracking).
     */
    @Override
    public void reconcile(List<LiveRoomCache> live, Map<UUID, Long> viewers, boolean pruneStale) {
        Set<String> members = pruneStale ? redis.opsForZSet().range(LiveRedisKeys.ranking(), 0, -1) : null;
        Set<String> liveIds = new HashSet<>();
        live.forEach(room -> liveIds.add(room.roomId().toString()));
        List<String> stale = members == null ? List.of()
                : members.stream().filter(member -> !liveIds.contains(member)).toList();

        if (stale.isEmpty() && live.isEmpty()) {
            return;
        }
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (String member : stale) {
                    operations.opsForZSet().remove(LiveRedisKeys.ranking(), member);
                    operations.delete(LiveRedisKeys.rawRoom(member));
                }
                for (LiveRoomCache room : live) {
                    writeRoom(operations, room);
                    String member = room.roomId().toString();
                    Long count = viewers.get(room.roomId());
                    if (count != null) {
                        operations.opsForZSet().add(LiveRedisKeys.ranking(), member, count);
                    } else {
                        // 이번 회차에 수를 못 읽은 방 — 직전 점수를 지키고, 처음 보는 방만 0 으로 넣는다.
                        operations.opsForZSet().addIfAbsent(LiveRedisKeys.ranking(), member, 0);
                    }
                }
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void writeRoom(RedisOperations operations, LiveRoomCache room) {
        Map<String, String> fields = new HashMap<>();
        putIfPresent(fields, FIELD_SELLER_ID, room.sellerId() == null ? null : room.sellerId().toString());
        putIfPresent(fields, FIELD_SELLER_NICKNAME, room.sellerNickname());
        putIfPresent(fields, FIELD_THUMBNAIL_URL, room.thumbnailUrl());
        putIfPresent(fields, FIELD_HLS_URL, room.hlsUrl());
        putIfPresent(fields, FIELD_TITLE, room.title());
        putIfPresent(fields, FIELD_STARTED_AT,
                room.startedAt() == null ? null : String.valueOf(room.startedAt().toEpochMilli()));
        operations.opsForHash().putAll(LiveRedisKeys.room(room.roomId()), fields);
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    private RankedRoom toRankedRoom(ZSetOperations.TypedTuple<String> tuple) {
        UUID roomId = parseUuidSafely(tuple.getValue());
        if (roomId == null) {
            return null;
        }
        long viewers = tuple.getScore() != null ? tuple.getScore().longValue() : 0L;
        return new RankedRoom(roomId, viewers);
    }

    private LiveRoomCache toCache(UUID roomId, Map<Object, Object> fields, long viewers) {
        return LiveRoomCache.builder()
                .roomId(roomId)
                .sellerId(parseUuidSafely((String) fields.get(FIELD_SELLER_ID)))
                .sellerNickname((String) fields.get(FIELD_SELLER_NICKNAME))
                .thumbnailUrl((String) fields.get(FIELD_THUMBNAIL_URL))
                .hlsUrl((String) fields.get(FIELD_HLS_URL))
                .pinnedProductName((String) fields.get(FIELD_PINNED_PRODUCT_NAME))
                .pinnedProductImageUrl((String) fields.get(FIELD_PINNED_PRODUCT_IMAGE_URL))
                .currentViewers(viewers)
                .title((String) fields.get(FIELD_TITLE))
                .startedAt(parseEpochMillisSafely((String) fields.get(FIELD_STARTED_AT)))
                .build();
    }

    /**
     * redis에서 받아온 tuple의 uuid가 올바른 uuid의 형태인지 검증
     */
    private UUID parseUuidSafely(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID format detected in Redis: {}", uuidStr);
            return null;
        }
    }

    /** 해시의 시작 시각이 숫자가 아니면 그 필드만 비운다 — 목록 전체를 깨뜨릴 이유가 없다. */
    private Instant parseEpochMillisSafely(String millis) {
        if (millis == null || millis.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis));
        } catch (NumberFormatException e) {
            log.warn("Invalid startedAt detected in Redis: {}", millis);
            return null;
        }
    }

    private record RankedRoom(UUID roomId, long viewers) {}
}
