package com.sapari.chat.infrastructure.redis;

import java.time.Duration;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatSubscription;
import com.sapari.chat.application.port.ProfanityDictionaryChangedSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * {@link ProfanityDictionaryChangedSource} 구현 — chat 소유 채널 {@code chat:dict:changed} 구독.
 *
 * <p>같은 모듈의 {@code RedisLiveRoomEndedSource} 와 같은 모양이다: 채널이 하나뿐이라(사전은
 * 글로벌 1개) 시작 시 1회 구독해 hot 공유 스트림으로 두고, 끊김은 backoff 재시도로 자가복구한다.
 *
 * <p>다른 점은 <b>payload 가 JSON 이 아니라 수 하나</b>라는 것이다. 발행이 chat 자신이고
 * ({@code ProfanityDictionarySnapshotRedisPublisher}) 실어야 할 것이 버전뿐이라 봉투를 만들지
 * 않았다 — 필드가 하나인 JSON 은 파서만 들여온다.
 *
 * <p>파싱 실패는 skip 한다(스트림 유지). 놓쳐도 받는 쪽의 주기 버전 확인이 받으므로, 여기서
 * 스트림을 죽이는 것이 더 나쁘다.
 */
@Slf4j
@Component
public class RedisProfanityDictionaryChangedSource implements ProfanityDictionaryChangedSource {

    private final Flux<Long> shared;

    public RedisProfanityDictionaryChangedSource(ReactiveStringRedisTemplate redis, ChatMetrics metrics) {
        this.shared = redis.listenToChannel(ChatRedisKeys.dictChanged())
                .flatMap(message -> parse(message.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> {
                            metrics.subscriptionResubscribed(ChatSubscription.PROFANITY_DICTIONARY);
                            log.warn("chat:dict:changed 구독 재시도 — 끊김 후 자가복구", s.failure());
                        }))
                .publish()
                .autoConnect(0);
    }

    @Override
    public Flux<Long> changed() {
        return shared;
    }

    /**
     * payload 는 버전 하나다. 깨진 값은 skip — 주기 확인이 받는다.
     *
     * <p>실패 로그에 <b>원문을 싣지 않는다</b>. 지금 계약엔 PII 가 없지만 신뢰경계 밖 문자열이라
     * 개행 삽입으로 로그를 위조할 수 있다. 길이는 남긴다 — "빈 값" 과 "숫자가 아닌 긴 값" 은
     * 원인이 다르다. (테스트 진입점)
     */
    Mono<Long> parse(String payload) {
        try {
            return Mono.just(Long.parseLong(payload.strip()));
        } catch (RuntimeException e) {
            log.error("chat:dict:changed payload 가 버전이 아니다 — skip payloadLength={}",
                    payload == null ? -1 : payload.length());
            return Mono.empty();
        }
    }
}
