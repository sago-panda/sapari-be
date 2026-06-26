package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.ChatMessageTypeMixin;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link ChatBroadcaster} 구현 — chat:pubsub:{roomId} 채널로 CHAT 봉투 발행/구독 (Pod 간 중계).
 *
 * <p><b>ObjectMapper</b>: 봉투를 다루는 ObjectMapper는 {@link ChatMessageTypeMixin} 등록이 의무다(누락 시
 * 첫 역직렬화에서 즉사 — 조용한 오염 아님). {@code ChatMessage.createdAt}(Instant) 때문에 JavaTimeModule도 등록.
 * publish/subscribe가 같은 인스턴스를 써 발행↔수신 직렬화 계약이 한 곳에 고정된다.
 *
 * <p><b>poison-message 생존</b>: 깨지거나 알 수 없는 봉투 1건이 구독 스트림 전체를 죽이면 그 Pod의 채팅 수신이
 * 통째로 멈춘다. 그래서 역직렬화 실패는 로그 후 해당 메시지만 skip 하고 스트림은 유지한다.
 *
 * <p>KICK_EVENT 발행은 api-app(KickUserService)이 StringRedisTemplate으로 직접 수행하므로 여기 없다(포트도 미선언).
 */
@Slf4j
@Component
public class RedisChatBroadcaster implements ChatBroadcaster {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisChatBroadcaster(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(ChatMessageType.class, ChatMessageTypeMixin.class);
    }

    @Override
    public Mono<Void> publish(UUID roomId, ChatMessage message) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(new ChatEnvelope.ChatMsg(message)))
                .flatMap(json -> redis.convertAndSend(ChatRedisKeys.pubsub(roomId), json))
                .then();
    }

    @Override
    public Flux<ChatEnvelope> subscribe(UUID roomId) {
        return redis.listenToChannel(ChatRedisKeys.pubsub(roomId))
                .flatMap(message -> deserialize(message.getMessage()));
    }

    private Mono<ChatEnvelope> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, ChatEnvelope.class));
        } catch (Exception e) {
            log.error("chat:pubsub 봉투 역직렬화 실패 — 해당 메시지 skip(구독 스트림 유지)", e);
            return Mono.empty();
        }
    }
}
