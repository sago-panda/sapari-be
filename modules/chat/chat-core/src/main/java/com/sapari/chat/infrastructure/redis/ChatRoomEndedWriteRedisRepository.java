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

    /**
     * 값과 만료를 기록 어댑터에서 <b>참조</b>한다 — 복사하지 않는다. 복사하면 한쪽을 고칠 때
     * 되돌린 마커가 원래보다 짧거나 길게 살아, 되돌리려던 상태와 다른 상태가 된다.
     * (같은 패키지라 {@code ChatRoomEndedRedisRepository} 의 두 상수를 그대로 읽는다.)
     *
     * <p>만료를 <b>처음부터 다시</b> 건다 — 남은 시간을 계산해 붙이지 않는다. 지우기 전의 잔여
     * 시간을 알 길이 없고(지웠으니까), 되돌림은 "방이 방금 끝난 것으로 드러났다" 는 뜻이라
     * 그 시점부터 보관 기간을 세는 것이 오히려 맞다.
     */
    @Override
    public void markEndedAgain(UUID roomId) {
        redisTemplate.opsForValue().set(ChatRedisKeys.roomEnded(roomId),
                ChatRoomEndedRedisRepository.MARKER, ChatRoomEndedRedisRepository.ENDED_TTL);
    }
}
