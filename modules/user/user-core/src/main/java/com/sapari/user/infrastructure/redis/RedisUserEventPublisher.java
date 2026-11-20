package com.sapari.user.infrastructure.redis;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.user.application.port.UserEventPublisher;

/**
 * {@link UserEventPublisher} 구현 — Redis Pub/Sub. 형제 {@code RedisLiveEventPublisher} 와 같은 모양이다.
 *
 * <p><b>payload 를 손으로 조립한다.</b> 값이 {@code UUID} 와 epochMillis {@code long} 둘뿐이라
 * 이스케이프가 필요한 문자가 나올 수 없고, 그래서 직렬화 의존을 하나 들이지 않는다. 필드를 늘릴 때
 * 문자열이 섞이면 이 판단이 무효가 된다 — 그때는 {@code ObjectMapper} 로 바꾼다.
 *
 * <p><b>발행 실패를 삼킨다(at-most-once).</b> 탈퇴 신청 자체는 이미 커밋됐고, 여기서 던지면
 * {@code afterCommit} 훅에서 예외가 나가 <b>이미 성공한 신청이 호출자에게 실패로 보인다</b>.
 *
 * <p>⚠️ 형제는 같은 자리에 "재조정이 백스톱" 이라고 적는데 <b>이쪽은 그 백스톱이 없다.</b> 놓친
 * 발행을 뒤늦게 메우는 경로가 없으므로, 구독자가 그 사건을 영구히 못 받는다. 그 결과는 구독자가
 * 정하는 것이지 여기서 보상할 수 없다 — 그래서 로그를 {@code error} 로 남긴다.
 *
 * <p>스테레오타입을 붙이는 근거(실측): {@code user-core} 를 쓰는 앱이 셋인데
 * {@code batch-app} 은 {@code com.sapari.user.infrastructure.<b>persistence</b>} 만 스캔해 이 패키지에
 * 닿지 않고(그 앱엔 redis 의존도 없다), {@code api-app}·{@code product-app} 은 {@code com.sapari} 를
 * 전부 스캔하며 둘 다 {@code redis-core} 를 의존한다. 그래서 Redis 없는 앱에서 조립이 깨지는 모양이
 * 생기지 않는다. 스캔 범위가 바뀌면 이 근거부터 다시 재야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUserEventPublisher implements UserEventPublisher {

    private final StringRedisTemplate redis;

    @Override
    public void publishWithdrawalRequested(UUID userId, Instant requestedAt) {
        String payload = "{\"userId\":\"" + userId + "\",\"requestedAt\":" + requestedAt.toEpochMilli() + "}";
        try {
            redis.convertAndSend(UserRedisKeys.accountWithdrawingChannel(), payload);
            log.info("탈퇴 신청 발행. userId={}", userId);
        } catch (RuntimeException e) {
            log.error("탈퇴 신청 발행 실패 — 구독자가 이 사건을 받지 못한다(보상 경로 없음). userId={}",
                    userId, e);
        }
    }
}
