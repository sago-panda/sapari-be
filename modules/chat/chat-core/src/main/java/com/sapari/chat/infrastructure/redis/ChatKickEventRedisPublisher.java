package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.application.port.ChatKickEventPublisher;
import com.sapari.chat.application.protocol.ChatEnvelope;

/**
 * {@code chat:pubsub:{roomId}} 채널로 강퇴 봉투를 내보내는 블로킹 어댑터.
 *
 * <p><b>봉투를 손으로 짓지 않는다.</b> 설계 문서는 이 발행을 "JSON을 직접 만들어 보낸다"로 적었는데, 그건
 * 봉투 타입이 streaming-app 안에만 있던 시절의 이야기다. 지금은 {@link ChatEnvelope}가 chat-core에 있어
 * 양쪽이 같은 타입을 본다 — 실제 타입을 직렬화하면 "받는 쪽과 바이트가 맞아야 한다"는 계약이 사람이
 * 지켜야 할 약속에서 컴파일러가 지키는 것으로 바뀐다. 손으로 지으면 필드명 한 글자가 틀려도 빌드는
 * 통과하고, 그 강퇴는 전 Pod에서 조용히 사라진다.
 *
 * <p>{@code ObjectMapper}를 직접 만든다. 강퇴 봉투는 {@code UUID} 한 필드뿐이라 시간 모듈도
 * MixIn 등록도 필요 없다 — 채팅 봉투를 다루는 {@code RedisChatBroadcaster}가 그 둘을 요구하는 것과
 * 다르다. 주입받지 않는 이유는 그 요구가 서로 다르기 때문이다: 앱이 가진 매퍼가 어떤 설정을 갖고 있든
 * 이 봉투의 모양은 바뀌지 않아야 한다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 어댑터는 그 스택을 가진 앱이 명시로 등록한다.
 */
public class ChatKickEventRedisPublisher implements ChatKickEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatKickEventRedisPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publishKicked(UUID roomId, UUID kickedUserId) {
        redisTemplate.convertAndSend(ChatRedisKeys.pubsub(roomId), serialize(kickedUserId));
    }

    /**
     * 봉투를 문자열로. 실패는 전파한다 — 발행하지 못한 강퇴는 전파되지 않은 강퇴이고, 그것을 성공이라
     * 부르면 당사자가 끊기지 않은 채로 남는다.
     */
    private String serialize(UUID kickedUserId) {
        try {
            return objectMapper.writeValueAsString(new ChatEnvelope.KickEvent(kickedUserId));
        } catch (JsonProcessingException e) {
            // 평탄한 record라 실제로는 도달하지 않는다. 도달했다면 봉투 계약이 바뀐 것이므로 조용히 넘기면 안 된다.
            throw new IllegalStateException("강퇴 봉투 직렬화 실패 roomId 무관 — 봉투 계약을 확인할 것", e);
        }
    }
}
