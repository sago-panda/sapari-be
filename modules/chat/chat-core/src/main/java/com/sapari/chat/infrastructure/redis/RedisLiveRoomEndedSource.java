package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.application.port.LiveRoomEndedSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * {@link LiveRoomEndedSource} 구현 — live 소유 채널 {@code live:room:ended} 구독.
 *
 * <p>payload JSON에서 {@code roomId}만 추출한다(endedAt은 정보성이라 무시). 채널은 하나뿐이라(전 방 공용)
 * 시작 시 1회 구독해 hot 공유 스트림으로 둔다. 파싱 실패(깨진 payload)는 skip하고, 채널 끊김은 backoff 재시도로
 * 자가복구해 상시 가동을 유지한다. (KICK_EVENT처럼 live가 손수 만든 JSON이라 타입 공유 없이 필드만 읽는다.)
 */
@Slf4j
@Component
public class RedisLiveRoomEndedSource implements LiveRoomEndedSource {

    /** live 소유 채널 계약(live 발행 / chat 구독). chat 소유 키가 아니라 ChatRedisKeys에 두지 않는다. */
    private static final String CHANNEL = "live:room:ended";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Flux<UUID> shared;

    public RedisLiveRoomEndedSource(ReactiveStringRedisTemplate redis) {
        this.shared = redis.listenToChannel(CHANNEL)
                .flatMap(message -> parse(message.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("live:room:ended 구독 재시도 — 끊김 후 자가복구", s.failure())))
                .publish()
                .autoConnect(0);
    }

    @Override
    public Flux<UUID> ended() {
        return shared;
    }

    /** payload {@code {"roomId":"<uuid>",...}} 에서 roomId 추출. 깨진 payload는 skip(스트림 유지). */
    Mono<UUID> parse(String json) {
        try {
            return Mono.just(UUID.fromString(objectMapper.readTree(json).get("roomId").asText()));
        } catch (Exception e) {
            log.error("live:room:ended payload 파싱 실패 — 해당 메시지 skip payload={}", json);
            return Mono.empty();
        }
    }
}
