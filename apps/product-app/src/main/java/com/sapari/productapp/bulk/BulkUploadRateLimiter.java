package com.sapari.productapp.bulk;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.sapari.product.domain.exception.BulkUploadRateLimitedException;
import com.sapari.product.domain.exception.BulkUploadUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 판매자별 대량 등록 한도(Redis). 판매자당 <b>동시 처리 1건</b>, <b>{@value #MAX_UPLOADS_PER_WINDOW}건 / 10분</b>.
 *
 * <p>요청 1건이 상품 100개 × 조합 최대 500개를 쓰므로, 같은 판매자가 반복·병렬로 호출하면 DB 커넥션과 파싱 CPU를 독점할 수 있다. 판정과 잠금 획득은
 * Lua 스크립트 하나로 원자적으로 한다 — 나눠 하면 두 요청이 동시에 "잠금 없음·한도 안"을 보고 함께 통과한다.
 *
 * <ul>
 *   <li><b>동시 1건</b>: 처리 중 표시를 토큰 값으로 {@code SET PX}한다. 해제는 토큰이 같을 때만 지운다 — 처리가 TTL보다 길어져 다음 요청이 새 잠금을
 *       잡은 뒤에 끝난 앞 요청이 남의 잠금을 지우지 않게 한다. TTL은 Pod가 죽어 해제가 안 될 때 판매자가 묶이는 최대 시간이다.
 *   <li><b>기간당 건수</b>: 고정 창(첫 요청 시점부터 10분) 카운터다. 창 경계에서 최대 2배가 짧게 몰릴 수 있지만 동시 1건 제한이 병렬 폭주를 막으므로 받아들인다.
 *       동시 처리로 거절된 요청은 건수를 쓰지 않는다(잠금을 먼저 본다). 한도 초과로 거절된 요청도 창 만료 시각을 늘리지 않는다.
 * </ul>
 *
 * <p><b>Redis 장애 시 거절(fail-closed)</b>: 한도를 확인하지 못하면 {@link BulkUploadUnavailableException}(503)으로 받지 않는다. 대량 등록은 단건 등록으로
 * 대체할 수 있어, 보호 없이 받는 것보다 잠시 막는 쪽을 택했다.
 *
 * <p>두 키는 판매자 id를 해시 태그({@code {...}})로 묶어 Redis Cluster에서도 같은 슬롯에 놓인다(Lua 스크립트의 다중 키 조건).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkUploadRateLimiter {

    /** 창 하나에서 받을 수 있는 업로드 수. */
    static final int MAX_UPLOADS_PER_WINDOW = 10;

    /** 건수 창 길이. */
    static final Duration WINDOW = Duration.ofMinutes(10);

    /** 처리 중 표시의 수명. 정상 처리 시간(초 단위)보다 넉넉히 길고, 해제 실패 시 판매자가 묶이는 상한이다. */
    static final Duration LOCK_TTL = Duration.ofMinutes(5);

    /** 다른 업로드를 처리 중이라 거절할 때 안내할 대기 시간. 잠금 TTL은 최악값이라 그대로 알려 주면 정상 처리보다 훨씬 길다. */
    static final Duration CONCURRENT_RETRY_AFTER = Duration.ofSeconds(10);

    private static final String KEY_PREFIX = "product:bulk-upload:{";

    private static final long ACQUIRED = 0L;
    private static final long BUSY = 1L;

    /**
     * 판정 + 획득. KEYS[1]=처리 중 표시, KEYS[2]=건수 창 / ARGV[1]=토큰, ARGV[2]=잠금 TTL(ms), ARGV[3]=창 한도, ARGV[4]=창 길이(ms).
     * 반환 {결과, 창 남은 ms}: 0=획득, 1=처리 중, 2=건수 초과.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE_SCRIPT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return {1, 0}
            end
            local count = redis.call('INCR', KEYS[2])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[2], ARGV[4])
            end
            if count > tonumber(ARGV[3]) then
              return {2, redis.call('PTTL', KEYS[2])}
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return {0, 0}
            """, List.class);

    /** 토큰이 같을 때만 처리 중 표시를 지운다. KEYS[1]=처리 중 표시 / ARGV[1]=토큰. */
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 판매자의 업로드 한 건을 시작한다. 통과하면 처리 중 표시를 잡고, 해제에 쓸 토큰을 돌려준다.
     *
     * @param sellerId 인증된 판매자 id
     * @return 해제용 토큰({@link #release}에 넘긴다)
     * @throws BulkUploadRateLimitedException 같은 판매자의 업로드를 처리 중이거나 창 한도를 넘은 경우(429)
     * @throws BulkUploadUnavailableException Redis에 접근하지 못해 한도를 판정할 수 없는 경우(503). 이때 잡혔을지 모를 처리 중 표시는
     *                                        같은 토큰으로 해제를 시도한 뒤 던진다
     */
    public String acquire(UUID sellerId) {
        String token = UUID.randomUUID().toString();
        List<?> result;
        try {
            result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(lockKey(sellerId), countKey(sellerId)),
                    token, String.valueOf(LOCK_TTL.toMillis()), String.valueOf(MAX_UPLOADS_PER_WINDOW),
                    String.valueOf(WINDOW.toMillis()));
        } catch (DataAccessException e) {
            // 응답만 타임아웃 나고 스크립트는 실행됐을 수 있다 — 그러면 처리 중 표시가 남아 재시도가 최대 LOCK_TTL 동안 429를 받는다.
            // 같은 토큰으로 한 번 해제를 시도한다(토큰 확인이라 다른 요청의 표시는 지우지 않고, 실패해도 release가 삼킨다).
            // 이미 쓴 건수 1건은 돌려주지 않는다 — 실행 여부를 알 수 없어 되돌리면 실행 안 된 경우 남의 건수를 깎는다.
            release(sellerId, token);
            throw new BulkUploadUnavailableException("대량 등록 한도 판정 실패(Redis): seller=" + sellerId, e);
        }

        long outcome = (Long) result.get(0);
        if (outcome == ACQUIRED) {
            return token;
        }
        if (outcome == BUSY) {
            throw new BulkUploadRateLimitedException(CONCURRENT_RETRY_AFTER.toSeconds(),
                    "같은 판매자의 대량 등록을 처리 중: seller=" + sellerId);
        }
        long remainingMillis = (Long) result.get(1);
        throw new BulkUploadRateLimitedException(ceilSeconds(remainingMillis),
                "대량 등록 건수 한도(" + MAX_UPLOADS_PER_WINDOW + "건/" + WINDOW.toMinutes() + "분) 초과: seller=" + sellerId);
    }

    /**
     * 업로드 처리를 끝내고 처리 중 표시를 내려놓는다. 토큰이 다르면(TTL이 지나 다른 요청이 잡았으면) 지우지 않는다.
     *
     * <p>해제 실패는 예외로 올리지 않고 로그만 남긴다. 이 시점엔 업로드 결과가 이미 커밋됐으므로, 응답을 실패로 바꾸면 클라이언트가 재업로드해
     * 상품이 중복 등록된다. 남은 표시는 {@link #LOCK_TTL} 뒤 만료되고, 그동안 같은 판매자의 다음 업로드만 429를 받는다.
     *
     * @param sellerId 판매자 id
     * @param token    {@link #acquire}가 돌려준 토큰
     */
    public void release(UUID sellerId, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey(sellerId)), token);
        } catch (DataAccessException e) {
            log.warn("대량 등록 처리 중 표시 해제 실패 — {}초 뒤 만료로 풀림: seller={}", LOCK_TTL.toSeconds(), sellerId, e);
        }
    }

    /**
     * 판매자의 처리 중 표시 키.
     */
    static String lockKey(UUID sellerId) {
        return KEY_PREFIX + sellerId + "}:lock";
    }

    /**
     * 판매자의 건수 창 키.
     */
    static String countKey(UUID sellerId) {
        return KEY_PREFIX + sellerId + "}:count";
    }

    /**
     * 남은 밀리초를 Retry-After 초로 올림한다(최소 1초 — 0이면 클라이언트가 즉시 재시도한다).
     */
    private static long ceilSeconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }
}
