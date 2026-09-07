package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
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

    /**
     * 재발행 억제 창. 회수는 다음 강퇴가 하므로 이보다 긴 간격의 반복은 그대로 통과한다 —
     * 이 값이 막는 것은 "지금 막 알린 것을 또 알리는" 연타뿐이다. 측정해서 고른 값이 아니다.
     */
    private static final Duration DEBOUNCE_WINDOW = Duration.ofSeconds(10);

    /** 값은 읽히지 않는다. 존재만이 의미다. */
    private static final String PRESENT = "1";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatAccountEventRedisPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 같은 사용자에 대해 <b>창 안에서 한 번만</b> 내보낸다.
     *
     * <p>발행 자체는 밴이 새로 걸렸든 이미 있던 것이든 일어나야 한다 — 이벤트를 놓친 Pod의 조용한
     * 세션을 회수하는 경로가 그것뿐이다. 그런데 그러면 <b>이미 밴된 대상을 반복 강퇴하는 것만으로</b>
     * 호출 횟수가 그대로 함대 전체 스캔 횟수가 된다. 이벤트 하나가 모든 Pod에서 로컬 세션을 두 번
     * 훑고(사유 전송 + 종료), 이 엔드포인트에는 레이트리밋이 없다.
     *
     * <p>창을 두면 둘을 다 얻는다. 회수는 <b>다음 강퇴</b>가 하므로 창보다 긴 간격이면 그대로 살아 있고,
     * 창 안의 반복은 첫 건이 이미 하고 있는 일이라 더 알릴 것이 없다.
     *
     * <p>{@code SET NX EX}라 판정과 예약이 한 번에 일어난다. 읽고 쓰면 동시 강퇴 둘이 같이 통과한다 —
     * 정확히 이 어댑터가 줄이려는 상황이 두 배로 일어난다.
     */
    @Override
    public void publishBanned(UUID userId) {
        Boolean firstInWindow = redisTemplate.opsForValue()
                .setIfAbsent(ChatRedisKeys.accountEventDebounce(userId), PRESENT, DEBOUNCE_WINDOW);
        if (!Boolean.TRUE.equals(firstInWindow)) {
            return;
        }
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
