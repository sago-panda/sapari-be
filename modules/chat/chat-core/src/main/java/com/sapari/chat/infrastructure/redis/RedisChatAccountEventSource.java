package com.sapari.chat.infrastructure.redis;

import java.time.Duration;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.application.port.ChatAccountEventSource;
import com.sapari.chat.application.protocol.ChatAccountEvent;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * {@code chat:account:events} 구독 — Pod당 하나, <b>상시 가동</b>.
 *
 * <p>{@code publish().autoConnect(0)}으로 구독자가 0이어도 업스트림을 끊지 않는다. 방 구독처럼 필요할 때
 * 붙였다 떼면 그 사이에 발행된 이벤트를 놓치는데, Pub/Sub은 영속이 아니라 놓친 것이 다시 오지 않는다 —
 * 그 사람은 밴이 걸렸는데도 세션이 살아 있는 채로 남는다.
 *
 * <p><b>에러로 끊기면</b> 무한 백오프로 다시 붙는다. 여기서 스트림이 죽으면 그 Pod는 <b>재시작 전까지</b>
 * 모든 계정 조치를 놓치므로, 포기하는 종료 조건을 두지 않는다.
 *
 * <p>다만 {@code retryWhen}이 덮는 것은 {@code onError}뿐이다. 업스트림이 <b>정상 완료</b>로 끝나면
 * 재구독하지 않는다 — 형제 어댑터 둘도 같은 모양이고, 정상 완료는 종료 절차에서 오는 신호라 그때
 * 다시 붙는 것이 오히려 틀린 동작이다. 자가복구의 범위는 에러 갈래까지다.
 *
 * <p><b>깨진 봉투 하나가 구독을 죽이지 않는다.</b> 역직렬화 실패는 그 건만 버리고 넘어간다. 로그에 원문을
 * 싣지 않는 것은 신뢰경계 밖 문자열이라 개행 삽입으로 로그를 위조할 수 있어서이고, 대신 진단에 필요한
 * 만큼(사유·길이)은 남긴다.
 */
@Slf4j
@Component
public class RedisChatAccountEventSource implements ChatAccountEventSource {

    /**
     * 모르는 필드에 죽지 않는다. 봉투에 필드가 하나 늘면 롤링 배포 중 구버전 Pod가 <b>전부</b> 이벤트를
     * 버리고, 밴 집행이 함대 전체에서 조용히 되돌아간다. 형제 리더 둘도 같은 선택을 했다 —
     * 방 종료 소스는 필요한 필드만 뽑고, WS 핸들러는 이 설정을 명시로 끈다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Flux<ChatAccountEvent> shared;

    public RedisChatAccountEventSource(ReactiveStringRedisTemplate redis) {
        this.shared = redis.listenToChannel(ChatRedisKeys.accountEvents())
                .flatMap(message -> parse(message.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("chat:account:events 구독 재시도 — 끊김 후 자가복구", s.failure())))
                .publish()
                .autoConnect(0);
    }

    @Override
    public Flux<ChatAccountEvent> events() {
        return shared;
    }

    /** 깨진 봉투 skip·구독 생존을 단위로 재기 위해 package-private — 레퍼런스({@code RedisLiveRoomEndedSource})와 같다. */
    Mono<ChatAccountEvent> parse(String payload) {
        try {
            return Mono.just(objectMapper.readValue(payload, ChatAccountEvent.class));
        } catch (Exception e) {
            log.error("계정 이벤트 역직렬화 실패 — 해당 건 skip(구독 유지) cause={} payloadLength={}",
                    e.getClass().getSimpleName(), payload == null ? 0 : payload.length());
            return Mono.empty();
        }
    }
}
