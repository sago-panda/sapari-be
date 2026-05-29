package com.sapari.live.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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

    private record RankedRoom(UUID roomId, long viewers) {}
}
