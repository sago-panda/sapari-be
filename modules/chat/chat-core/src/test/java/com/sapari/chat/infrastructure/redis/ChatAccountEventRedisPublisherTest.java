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
import com.sapari.chat.application.protocol.ChatAccountEvent;

/**
 * 계정 봉투가 <b>받는 쪽이 복원할 수 있는 모양</b>으로 나가는지 고정한다.
 *
 * <p>컨테이너를 쓰지 않는다. 여기서 깨질 수 있는 것은 Redis의 동작이 아니라 <b>채널 이름과 봉투 바이트</b>
 * 둘이고, 그 둘은 나간 문자열만 붙잡으면 확인된다. 이 발행이 유실되는 경로는 "Redis가 못 받았다"가 아니라
 * "받았는데 아무도 못 읽는다"다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAccountEventPublisher — 봉투는 수신 측이 그대로 복원한다")
class ChatAccountEventRedisPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Captor
    private ArgumentCaptor<String> channelCaptor;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("⭐ 계정 채널로 나간다 — chat:pubsub 아래로 가면 전 Pod가 파싱 실패로 버린다")
    void publishesToTheAccountChannel() {
        // when
        new ChatAccountEventRedisPublisher(redisTemplate).publishBanned(userId);

        // then
        then(redisTemplate).should().convertAndSend(channelCaptor.capture(), payloadCaptor.capture());
        assertThat(channelCaptor.getValue()).isEqualTo("chat:account:events");
    }

    @Test
    @DisplayName("⭐ 수신 측이 같은 값으로 복원한다 — 필드명 한 글자가 틀리면 봉투가 조용히 사라진다")
    void roundTripsThroughTheReceiversMapper() throws Exception {
        // given
        new ChatAccountEventRedisPublisher(redisTemplate).publishBanned(userId);
        then(redisTemplate).should().convertAndSend(channelCaptor.capture(), payloadCaptor.capture());

        // when: 수신 측과 같은 설정(기본 매퍼)으로 되돌린다
        ChatAccountEvent restored = new ObjectMapper()
                .readValue(payloadCaptor.getValue(), ChatAccountEvent.class);

        // then
        assertThat(restored).isEqualTo(new ChatAccountEvent.Banned(userId));
    }

    @Test
    @DisplayName("봉투에 실리는 것은 종류와 사용자뿐이다 — 필드가 늘면 그 자체가 롤링 배포 사건이다")
    void carriesOnlyKindAndUser() throws Exception {
        // given
        new ChatAccountEventRedisPublisher(redisTemplate).publishBanned(userId);
        then(redisTemplate).should().convertAndSend(channelCaptor.capture(), payloadCaptor.capture());

        // when
        var json = new ObjectMapper().readTree(payloadCaptor.getValue());

        // then: PII가 실리지 않는다는 것이기도 하다
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get("kind").asText()).isEqualTo("BANNED");
        assertThat(json.get("userId").asText()).isEqualTo(userId.toString());
    }
}
