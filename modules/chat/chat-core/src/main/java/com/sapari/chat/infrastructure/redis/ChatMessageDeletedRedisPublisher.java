package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.application.port.ChatMessageDeletedPublisher;
import com.sapari.chat.application.protocol.ChatEnvelope;

/**
 * 삭제 봉투를 방 채널({@code chat:pubsub:{roomId}})로 내보낸다. 강퇴 발행({@code ChatKickEventRedisPublisher})과 같은 모양이다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 Redis 템플릿을 가진 앱이 명시로 등록한다.
 */
public class ChatMessageDeletedRedisPublisher implements ChatMessageDeletedPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatMessageDeletedRedisPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publishDeleted(UUID roomId, String messageId) {
        redisTemplate.convertAndSend(ChatRedisKeys.pubsub(roomId), serialize(messageId));
    }

    private String serialize(String messageId) {
        try {
            return objectMapper.writeValueAsString(new ChatEnvelope.MessageDeleted(messageId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("삭제 봉투 직렬화 실패 — 봉투 계약을 확인할 것", e);
        }
    }
}
