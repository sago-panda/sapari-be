package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.ChatMessageTypeMixin;
import com.sapari.chat.domain.model.ChatMessageType;

/**
 * 강퇴 봉투가 <b>받는 쪽이 복원할 수 있는 모양</b>으로 나가는지 고정한다.
 *
 * <p>컨테이너를 쓰지 않는다. 여기서 깨질 수 있는 것은 Redis의 동작이 아니라 <b>채널 이름과 봉투 바이트</b>
 * 둘이고, 그 둘은 나간 문자열만 붙잡으면 그대로 확인된다. 실제로 이 발행이 유실되는 경로는 "Redis가
 * 못 받았다"가 아니라 "받았는데 아무도 못 읽는다"였다.
 *
 * <p>복원할 때 쓰는 매퍼를 <b>수신 측과 같은 설정</b>으로 만든다({@code RedisChatBroadcaster}가 시간 모듈과
 * MixIn을 등록한다). 발행 측은 강퇴 봉투에 그 둘이 필요 없어 등록하지 않는데, 그 비대칭이 실제로 문제가
 * 없는지를 여기서 확인하는 셈이다 — 한쪽만 보고 통과시키면 계약이 갈린 걸 못 잡는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatKickEventPublisher — 봉투는 수신 측이 그대로 복원한다")
class ChatKickEventRedisPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Captor
    private ArgumentCaptor<String> channelCaptor;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    private final UUID roomId = UUID.randomUUID();
    private final UUID kickedUserId = UUID.randomUUID();

    /** 수신 측({@code RedisChatBroadcaster})과 같은 설정의 매퍼. */
    private ObjectMapper receiverMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(ChatMessageType.class, ChatMessageTypeMixin.class);
    }

    @Test
    @DisplayName("방의 chat:pubsub 채널로 나간다 — 채널이 어긋나면 아무도 못 받는다")
    void publishesToRoomChannel() {
        // when
        new ChatKickEventRedisPublisher(redisTemplate).publishKicked(roomId, kickedUserId);

        // then
        then(redisTemplate).should().convertAndSend(channelCaptor.capture(), payloadCaptor.capture());
        assertThat(channelCaptor.getValue()).isEqualTo("chat:pubsub:" + roomId);
    }

    @Test
    @DisplayName("수신 측 매퍼로 KickEvent가 그대로 복원된다 — 필드명 한 글자가 틀리면 전 Pod에서 조용히 사라진다")
    void payloadRoundTripsIntoKickEvent() throws Exception {
        // given
        new ChatKickEventRedisPublisher(redisTemplate).publishKicked(roomId, kickedUserId);
        then(redisTemplate).should().convertAndSend(channelCaptor.capture(), payloadCaptor.capture());

        // when: 받는 쪽이 하는 일을 그대로 한다 — 봉투 타입으로 역직렬화
        ChatEnvelope restored = receiverMapper().readValue(payloadCaptor.getValue(), ChatEnvelope.class);

        // then
        assertThat(restored).isInstanceOf(ChatEnvelope.KickEvent.class);
        assertThat(((ChatEnvelope.KickEvent) restored).userId()).isEqualTo(kickedUserId);
    }

    @Test
    @DisplayName("와이어에는 kind와 userId 둘만 실린다 — 손으로 만든 JSON과 바이트가 맞아야 한다")
    void payloadCarriesOnlyKindAndUserId() throws Exception {
        // given
        new ChatKickEventRedisPublisher(redisTemplate).publishKicked(roomId, kickedUserId);
        then(redisTemplate).should().convertAndSend(channelCaptor.capture(), payloadCaptor.capture());

        // when
        var json = receiverMapper().readTree(payloadCaptor.getValue());

        // then: 필드가 늘면 계약이 바뀐 것이라 문서·프론트와 함께 움직여야 한다
        assertThat(json.get("kind").asText()).isEqualTo("KICK_EVENT");
        assertThat(json.get("userId").asText()).isEqualTo(kickedUserId.toString());
        assertThat(json.size()).isEqualTo(2);
    }
}
