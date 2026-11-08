package com.sapari.chat.infrastructure.redis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.chat.domain.repository.ChatViewerCountRepository;
import com.sapari.global.time.TimeProvider;

import lombok.RequiredArgsConstructor;

/**
 * 방별 고유 시청자 수 어댑터(블로킹). 방마다 {@code HVALS} 를 pipeline 한 번에 보낸다.
 *
 * <p><b>세는 규칙을 복사하지 않는다</b> — 낡음 판정({@link ChatSessionRedisRepository#STALE_AFTER})과 값 해석
 * ({@link ChatSessionRedisRepository#liveUserId})을 그쪽에서 참조한다. 복사하면 한쪽만 고쳤을 때 채팅 화면과
 * 라이브 목록의 시청자 수가 서로 다른 값이 된다.
 *
 * <p>스테레오타입을 붙이지 않는다. 블로킹 Redis 빈이 없는 streaming-app 에 스캔되면 부팅이 깨진다 —
 * 블로킹 스택을 가진 앱(live-app)이 명시로 등록한다({@code ChatRoomEndedWriteRedisRepository} 와 같다).
 */
@RequiredArgsConstructor
public class ChatViewerCountRedisRepository implements ChatViewerCountRepository {

    private final StringRedisTemplate redisTemplate;
    private final TimeProvider timeProvider;

    @Override
    public Map<UUID, Long> countViewers(Collection<UUID> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> rooms = new ArrayList<>(roomIds);
        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (UUID roomId : rooms) {
                    operations.opsForHash().values(ChatRedisKeys.sessions(roomId));
                }
                return null;
            }
        });

        Instant staleBefore = timeProvider.now().minus(ChatSessionRedisRepository.STALE_AFTER);
        Map<UUID, Long> counts = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            List<?> values = (List<?>) results.get(i);
            long viewers = values == null ? 0L : values.stream()
                    .map(String::valueOf)
                    .map(value -> ChatSessionRedisRepository.liveUserId(value, staleBefore))
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            counts.put(rooms.get(i), viewers);
        }
        return counts;
    }
}
