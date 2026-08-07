package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonMappingException;
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
import reactor.util.retry.Retry;

/**
 * {@link ChatBroadcaster} 구현 — chat:pubsub:{roomId} 채널로 CHAT 봉투 발행/구독 (Pod 간 중계).
 *
 * <p><b>구독 모델 = 패턴 구독(a)</b> (정본 §8.2, 2026-06-26): 시작 시 {@code chat:pubsub:*}를 1회 패턴 구독해
 * <b>상시 가동 hot 스트림</b>으로 둔다. {@link #subscribe(UUID)}는 그 공유 스트림을 roomId로 필터링할 뿐이라,
 * 방별 lazy 구독(b)이 갖는 <i>구독 활성 전 메시지 유실 레이스</i>와 ref-count lifecycle 버그가 원천적으로 없다.
 * 비용(모든 방 메시지 수신)은 <b>채널명에서 roomId만 먼저 추출</b>(역직렬화 없이)하고, 실제 구독 중인 방만
 * {@code subscribe()}에서 역직렬화하므로 비로컬 방 파싱 비용이 0이다. 동시 방이 수십 개+로 커지고 Redis egress가
 * 꼬리에 닿으면 어댑터만 Redis Streams로 교체(포트 시그니처 불변).
 *
 * <p><b>ObjectMapper</b>: 봉투를 다루는 ObjectMapper는 {@link ChatMessageTypeMixin} 등록이 의무(누락 시 첫
 * 역직렬화 즉사). {@code ChatMessage.createdAt}(Instant) 때문에 JavaTimeModule도 등록.
 *
 * <p><b>poison-message 생존</b>: 깨진 채널명/봉투 1건이 구독 스트림을 죽이지 않도록 skip(log)한다.
 * 패턴 구독 자체가 Redis 재접속 등으로 끊기면 backoff 재시도로 자가 복구해 "상시 가동"을 유지한다.
 */
@Slf4j
@Component
public class RedisChatBroadcaster implements ChatBroadcaster {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 상시 가동 hot 스트림 — 채널명에서 roomId만 뽑은 raw 메시지(역직렬화 전). */
    private final Flux<RawMessage> shared;

    private record RawMessage(UUID roomId, String body) {
    }

    public RedisChatBroadcaster(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .addMixIn(ChatMessageType.class, ChatMessageTypeMixin.class);

        // publish().autoConnect(0): 생성 즉시 업스트림 연결 + 구독자 0이어도 끊지 않음(상시 가동 → 구독 레이스 제거).
        this.shared = redis.listenToPattern(ChatRedisKeys.pubsubPattern())
                .mapNotNull(msg -> toRaw(msg.getChannel(), msg.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("chat:pubsub 패턴 구독 재시도 — 끊김 후 자가복구", s.failure())))
                .publish()
                .autoConnect(0);
    }

    @Override
    public Mono<Void> publish(UUID roomId, ChatMessage message) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(new ChatEnvelope.ChatMsg(message)))
                .flatMap(json -> redis.convertAndSend(ChatRedisKeys.pubsub(roomId), json))
                .then();
    }

    @Override
    public Flux<ChatEnvelope> subscribe(UUID roomId) {
        // roomId 필터를 역직렬화보다 먼저 → 매칭 방만 파싱(비로컬 방은 여기서 걸러져 파싱 안 됨)
        return shared.filter(raw -> roomId.equals(raw.roomId()))
                .flatMap(raw -> deserialize(raw.body()));
    }

    /** 채널명(chat:pubsub:{roomId})에서 roomId만 추출 — 역직렬화 없이 라우팅 키 확보. 깨진 채널은 null로 skip. */
    private RawMessage toRaw(String channel, String body) {
        if (channel == null || !channel.startsWith(ChatRedisKeys.PUBSUB_PREFIX)) {
            return null;
        }
        try {
            UUID roomId = UUID.fromString(channel.substring(ChatRedisKeys.PUBSUB_PREFIX.length()));
            return new RawMessage(roomId, body);
        } catch (IllegalArgumentException e) {
            // 채널명도 payload와 같은 신뢰경계 밖 문자열이라 원문을 싣지 않는다 — 개행을 섞어 로그 줄을
            // 위조할 수 있다. 여기 오는 건 우리 발행이 아니다(우리는 UUID로 키를 만든다). 남의 발행이
            // 잘못됐다는 사실과 규모만 알면 되므로 접미사 길이로 갈음한다.
            log.error("chat:pubsub 채널명 roomId 파싱 실패 — skip suffixLength={}",
                    channel.length() - ChatRedisKeys.PUBSUB_PREFIX.length());
            return null;
        }
    }

    private Mono<ChatEnvelope> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, ChatEnvelope.class));
        } catch (Exception e) {
            // 예외 객체·메시지를 그대로 찍지 않는다 — InvalidFormatException 등은 실패한 "값"을 메시지에 인용하므로
            // 봉투의 senderEmail 같은 PII가 로그로 샌다. 진단에 필요한 건 실패 종류와 위치지 값이 아니다.
            log.error("chat:pubsub 봉투 역직렬화 실패 — 해당 메시지 skip(구독 스트림 유지) cause={} path={}",
                    e.getClass().getSimpleName(), failedFieldPath(e));
            return Mono.empty();
        }
    }

    /** 역직렬화가 걸린 필드 경로만 뽑는다(값 제외) — 예: message.senderId. 경로를 못 얻으면 "-". (테스트 진입점) */
    String failedFieldPath(Exception e) {
        if (!(e instanceof JsonMappingException mappingException)) {
            return "-";
        }
        String path = mappingException.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(Objects::nonNull)
                .map(RedisChatBroadcaster::safeFieldName)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "-" : path;
    }

    /**
     * 필드명을 로그에 넣기 전에 거른다.
     *
     * <p>우리 타입의 필드명은 전부 그대로 통과한다. 문제는 <b>봉투에 없는 필드</b>가 왔을 때다 —
     * 그때 예외 경로에 담기는 이름은 우리가 지은 게 아니라 발행한 쪽이 지은 것이라, 개행을 섞으면
     * 로그에 없는 줄을 만들어낼 수 있다. 값이 아니라 이름이라 방심하기 쉬운 자리다.
     */
    private static String safeFieldName(String name) {
        String filtered = name.replaceAll("[^A-Za-z0-9_$]", "");
        return filtered.length() <= MAX_FIELD_NAME_LENGTH ? filtered
                : filtered.substring(0, MAX_FIELD_NAME_LENGTH);
    }

    /** 필드명 로그 길이 상한 — 우리 필드명은 한참 아래라 정상 진단은 손실 없다. */
    private static final int MAX_FIELD_NAME_LENGTH = 64;
}
