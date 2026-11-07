package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapari.chat.application.port.AccountWithdrawingSource;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * {@link AccountWithdrawingSource} 구현 — user 소유 채널 {@code user:account:withdrawing} 구독.
 *
 * <p>형제 {@link RedisLiveRoomEndedSource} 와 같은 모양이다: payload 에서 쓰는 필드만 뽑고,
 * 채널은 하나뿐이라 시작 시 1회 구독해 hot 공유 스트림으로 두고, 파싱 실패는 skip 하고, 채널 끊김은
 * backoff 재시도로 자가복구한다.
 *
 * <p>⚠️ <b>자가복구되는 것은 구독이고 사건이 아니다.</b> Redis Pub/Sub 은 보존이 없으므로 끊겨
 * 있던 1~30초 동안 이 Pod 로 온 발행은 <b>사라진다</b>. 형제는 같은 모양이어도 지속 마커가
 * 백스톱이라 뒤늦게 메워지는데({@code chat:room:{id}:ended} 를 전송 경로가 다시 읽는다) 이쪽은
 * 그 백스톱이 없다 — 그래서 끊김 창의 탈퇴는 이 Pod 에서 영구 유실이고, 그 결과의 상한은
 * {@code AccountWithdrawingHandler} javadoc 이 축별로 적는다.
 */
@Slf4j
@Component
public class RedisAccountWithdrawingSource implements AccountWithdrawingSource {

    /** user 소유 채널 계약(user 발행 / chat 구독). chat 소유 키가 아니라 ChatRedisKeys 에 두지 않는다. */
    private static final String CHANNEL = "user:account:withdrawing";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Flux<UUID> shared;

    public RedisAccountWithdrawingSource(ReactiveStringRedisTemplate redis) {
        this.shared = redis.listenToChannel(CHANNEL)
                .flatMap(message -> parse(message.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("user:account:withdrawing 구독 재시도 — 끊김 후 자가복구",
                                s.failure())))
                .publish()
                .autoConnect(0);
    }

    @Override
    public Flux<UUID> withdrawing() {
        return shared;
    }

    /**
     * payload {@code {"userId":"<uuid>",...}} 에서 userId 추출. 깨진 payload 는 skip(스트림 유지).
     *
     * <p>실패 로그에 <b>원문을 싣지 않는다</b> — 형제와 같은 이유에 하나가 더 붙는다. 이 채널은
     * 계정 사건이라 user 가 필드를 늘릴 때 이름·이메일 같은 PII 가 들어올 자리가 방 종료보다 가깝고,
     * 신뢰경계 밖 문자열이라 개행 삽입으로 로그를 위조할 수도 있다. 진단에 필요한 만큼만 남긴다 —
     * 사유와 payload 길이. user 가 계약을 바꿔 전 탈퇴가 통째로 유실되는 상황에서 "무엇이 어떻게
     * 어긋났는지" 를 이 둘로 좁힐 수 있어야 한다.
     */
    Mono<UUID> parse(String json) {
        try {
            JsonNode userId = objectMapper.readTree(json).get("userId");
            if (userId == null) {
                // "필드가 없음" 과 "값이 이상함" 은 원인이 다르므로 NPE 에 기대지 않고 가른다.
                log.error("user:account:withdrawing payload 에 userId 필드 없음 — 해당 메시지 skip"
                        + " payloadLength={}", json.length());
                return Mono.empty();
            }
            return Mono.just(UUID.fromString(userId.asText()));
        } catch (Exception e) {
            log.error("user:account:withdrawing payload 파싱 실패 — 해당 메시지 skip cause={}"
                    + " payloadLength={}", e.getClass().getSimpleName(), json == null ? -1 : json.length());
            return Mono.empty();
        }
    }
}
