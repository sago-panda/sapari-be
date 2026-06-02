package com.sapari.live.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import com.sapari.live.domain.model.LiveRoomCache;

@ExtendWith(MockitoExtension.class)
class LiveRoomCacheRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    @SuppressWarnings("rawtypes")
    private ZSetOperations zSetOperations;

    private LiveRoomCacheRedisRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = new LiveRoomCacheRedisRepository(redis);
        given(redis.opsForZSet()).willReturn(zSetOperations);
    }

    @Test
    @DisplayName("redis에 아이템이 10개 이상일 때 상위 10개를 조회수 내림차순으로 반환한다")
    @SuppressWarnings("unchecked")
    void findTopByViewers_returnTop10_whenMoreThan10Items() {
        // given
        int limit = 10;
        List<TypedTuple<String>> tuples = buildTuples(15);
        Set<TypedTuple<String>> top10 = new LinkedHashSet<>(tuples.subList(0, limit));
        given(zSetOperations.reverseRangeWithScores(eq(LiveRedisKeys.ranking()), eq(0L), eq((long) limit - 1)))
                .willReturn(top10);
        List<Object> hashResults1 = buildHashResults(top10);
        given(redis.executePipelined(any(SessionCallback.class)))
                .willReturn(hashResults1);

        // when
        List<LiveRoomCache> result = repository.findTopByViewers(limit);

        // then
        assertThat(result).hasSize(10);
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).currentViewers())
                    .isGreaterThanOrEqualTo(result.get(i + 1).currentViewers());
        }
    }

    @Test
    @DisplayName("redis에 아이템이 10개 미만일 때 전체를 조회수 내림차순으로 반환한다")
    @SuppressWarnings("unchecked")
    void findTopByViewers_returnAll_whenLessThan10Items() {
        // given
        int limit = 10;
        List<TypedTuple<String>> tuples = buildTuples(5);
        Set<TypedTuple<String>> tupleSet = new LinkedHashSet<>(tuples);
        given(zSetOperations.reverseRangeWithScores(eq(LiveRedisKeys.ranking()), eq(0L), eq((long) limit - 1)))
                .willReturn(tupleSet);
        List<Object> hashResults2 = buildHashResults(tupleSet);
        given(redis.executePipelined(any(SessionCallback.class)))
                .willReturn(hashResults2);

        // when
        List<LiveRoomCache> result = repository.findTopByViewers(limit);

        // then
        assertThat(result).hasSize(5);
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).currentViewers())
                    .isGreaterThanOrEqualTo(result.get(i + 1).currentViewers());
        }
    }

    @Test
    @DisplayName("ZSET에 잘못된 UUID 형태의 roomId가 있으면 해당 항목은 결과에 포함되지 않는다")
    @SuppressWarnings("unchecked")
    void findTopByViewers_skipsInvalidUuid() {
        // given
        int limit = 10;
        TypedTuple<String> validTuple = buildTuple(UUID.randomUUID().toString(), 100L);
        TypedTuple<String> invalidTuple = buildTuple("not-a-valid-uuid", 200L);

        Set<TypedTuple<String>> tupleSet = new LinkedHashSet<>();
        tupleSet.add(invalidTuple);
        tupleSet.add(validTuple);

        given(zSetOperations.reverseRangeWithScores(eq(LiveRedisKeys.ranking()), eq(0L), eq((long) limit - 1)))
                .willReturn(tupleSet);
        // invalid UUID는 pre-filter에서 제거되므로 pipeline 결과는 valid 1개
        given(redis.executePipelined(any(SessionCallback.class)))
                .willReturn(List.of(buildHashFields()));

        // when
        List<LiveRoomCache> result = repository.findTopByViewers(limit);

        // then
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("redis에 값이 없으면 빈 리스트를 반환한다")
    @SuppressWarnings("unchecked")
    void findTopByViewers_returnsEmptyList_whenRedisIsEmpty() {
        // given
        given(zSetOperations.reverseRangeWithScores(any(), anyLong(), anyLong()))
                .willReturn(Set.of());

        // when
        List<LiveRoomCache> result = repository.findTopByViewers(10);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("반환된 LiveRoomCache 목록에서 roomId와 sellerId는 null이 없다")
    @SuppressWarnings("unchecked")
    void findTopByViewers_resultHasNoNullIdFields() {
        // given
        int limit = 10;
        List<TypedTuple<String>> tuples = buildTuples(5);
        Set<TypedTuple<String>> tupleSet = new LinkedHashSet<>(tuples);
        given(zSetOperations.reverseRangeWithScores(eq(LiveRedisKeys.ranking()), eq(0L), eq((long) limit - 1)))
                .willReturn(tupleSet);
        List<Object> hashResults5 = buildHashResults(tupleSet);
        given(redis.executePipelined(any(SessionCallback.class)))
                .willReturn(hashResults5);

        // when
        List<LiveRoomCache> result = repository.findTopByViewers(limit);

        // then
        assertThat(result).isNotEmpty();
        result.forEach(cache -> {
            assertThat(cache.roomId()).isNotNull();
            assertThat(cache.sellerId()).isNotNull();
        });
    }

    /** score 내림차순 (count*10, (count-1)*10, ...) 순으로 tuples 생성 */
    private List<TypedTuple<String>> buildTuples(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> buildTuple(UUID.randomUUID().toString(), (long) (count - i) * 10))
                .collect(Collectors.toList());
    }

    private TypedTuple<String> buildTuple(String value, long score) {
        TypedTuple<String> tuple = mock(TypedTuple.class);
        lenient().when(tuple.getValue()).thenReturn(value);
        lenient().when(tuple.getScore()).thenReturn((double) score);
        return tuple;
    }

    private List<Object> buildHashResults(Set<TypedTuple<String>> tuples) {
        return tuples.stream()
                .filter(t -> isValidUuid(t.getValue()))
                .map(t -> (Object) buildHashFields())
                .collect(Collectors.toList());
    }

    private boolean isValidUuid(String value) {
        if (value == null) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Map<Object, Object> buildHashFields() {
        return Map.of(
                "sellerId", UUID.randomUUID().toString(),
                "sellerNickname", "테스트판매자",
                "thumbnailUrl", "https://cdn.example.com/thumb/test",
                "hlsUrl", "https://hls.example.com/live/test/index.m3u8",
                "pinnedProductName", "테스트상품",
                "pinnedProductImageUrl", "https://cdn.example.com/product/test"
        );
    }
}
