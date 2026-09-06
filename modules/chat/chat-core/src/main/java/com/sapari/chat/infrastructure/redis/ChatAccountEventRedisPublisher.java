package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.application.port.ChatAccountEventPublisher;
import com.sapari.chat.application.protocol.ChatAccountEvent;

/**
 * {@code chat:account:events} 채널로 계정 조치를 내보내는 블로킹 어댑터.
 *
 * <p><b>봉투를 손으로 짓지 않는다.</b> 받는 쪽과 바이트가 맞아야 하는데, 손으로 지은 JSON은 필드명 한
 * 글자가 틀려도 빌드가 통과하고 그 봉투는 전 Pod에서 조용히 사라진다. 실제 타입을 직렬화하면 그 일치가
 * 사람이 지킬 약속이 아니라 컴파일러가 지키는 것이 된다.
 *
 * <p>{@code ObjectMapper}를 직접 만든다. 이 봉투는 {@code UUID} 한 필드뿐이라 시간 모듈도 MixIn도
 * 필요 없고, 앱이 가진 매퍼가 어떤 설정을 갖고 있든 이 봉투의 모양은 바뀌지 않아야 한다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 어댑터는 그 스택을 가진 앱이 명시로 등록한다.
 */
public class ChatAccountEventRedisPublisher implements ChatAccountEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatAccountEventRedisPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publishBanned(UUID userId) {
        redisTemplate.convertAndSend(ChatRedisKeys.accountEvents(), serialize(new ChatAccountEvent.Banned(userId)));
    }

    /**
     * 봉투를 문자열로. 실패는 전파한다 — 발행하지 못한 밴은 다른 방의 세션에 닿지 못한 밴이다.
     */
    private String serialize(ChatAccountEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 평탄한 record라 실제로는 도달하지 않는다. 도달했다면 봉투 계약이 바뀐 것이므로 조용히 넘기면 안 된다.
            throw new IllegalStateException("계정 이벤트 직렬화 실패 — 봉투 계약을 확인할 것", e);
        }
    }
}
