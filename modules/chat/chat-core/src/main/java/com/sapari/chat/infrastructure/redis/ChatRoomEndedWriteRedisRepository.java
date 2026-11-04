package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.chat.domain.repository.ChatRoomEndedWriteRepository;

import lombok.RequiredArgsConstructor;

/**
 * 종료 마커 해제 어댑터(블로킹).
 *
 * <p>이 키는 타입 충돌에 노출되지 않는다 — 기록이 {@code SET}, 조회가 {@code EXISTS} 라 남이 같은
 * 이름을 먼저 써도 WRONGTYPE 이 나지 않는다. 그래서 강퇴 명단에서 한 {@code KickStoreCorruptedException}
 * 분류를 여기까지 넓히지 않는다({@code ChatRoomEndedRedisRepository} javadoc 의 같은 판단이다).
 *
 * <p>스테레오타입을 붙이지 않는다. 블로킹 Redis 빈이 없는 streaming-app 에 스캔되면 그 앱의 부팅이
 * 깨진다 — 이 어댑터는 블로킹 스택을 가진 앱이 명시로 등록한다.
 */
@RequiredArgsConstructor
public class ChatRoomEndedWriteRedisRepository implements ChatRoomEndedWriteRepository {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void clearEnded(UUID roomId) {
        redisTemplate.delete(ChatRedisKeys.roomEnded(roomId));
    }
}
