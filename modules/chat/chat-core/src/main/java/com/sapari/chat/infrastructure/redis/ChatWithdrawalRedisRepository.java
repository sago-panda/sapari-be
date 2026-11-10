package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.repository.ChatWithdrawalRepository;
import com.sapari.chat.domain.repository.ChatWithdrawalWriteRepository;

import reactor.core.publisher.Mono;

/**
 * {@code chat:withdrawing:{userId}} 어댑터 — 키 존재가 곧 탈퇴 신청이다.
 *
 * <p><b>수명({@code chat.withdrawal.record-ttl}, 기본 24h)의 하한은 chat 이 읽을 수 없는 값이다.</b> 탈퇴
 * 전에 발급된 룸 토큰의 남은 수명 — live 의 {@code live.room-token.expiration-seconds}(예시 설정 기본 90s)
 * — 이상이어야 한다. 폐기({@code revokeAllSessions})가 끝난 뒤에는 새 룸 토큰을 받을 수 없다 — live-app 의
 * {@code TokenRevocationFilter} 가 요청마다 폐기를 보고, 발급(캐시 포함)은 그 뒤 컨트롤러 안이라 우회할
 * 길이 없다(구조상이고 측정이 아니다). 다만 탈퇴 이벤트는 폐기보다 <b>먼저</b> 나가므로, 그 사이에 받은
 * 토큰도 있을 수 있다. 그 토큰까지 덮는 것은 폐기가 아니라 이 수명이 토큰 만료보다 몇 자릿수 길다는
 * 사실이다. chat 은 그 값을 읽을 수 없지만, live 가 그것을 300초 이하로 묶고 넘으면 기동을 멈춘다
 * ({@code RoomTokenProperties}). 그래서 이 수명은 300초 이상이기만 하면 된다.
 *
 * <p>길게 둬서 잃는 것이 없다. 탈퇴 신청을 되돌리는 경로가 없고(user 도메인에 취소 0건), 키 하나는
 * 수십 바이트다. 되돌리는 경로가 생기면 그 변경이 이 키의 삭제를 함께 가져와야 한다.
 */
@Repository
public class ChatWithdrawalRedisRepository implements ChatWithdrawalRepository, ChatWithdrawalWriteRepository {

    /** 값은 읽히지 않는다. 존재만이 의미다. */
    private static final String PRESENT = "1";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration recordTtl;

    public ChatWithdrawalRedisRepository(ReactiveStringRedisTemplate redisTemplate,
                                         @Value("${chat.withdrawal.record-ttl:24h}") Duration recordTtl) {
        if (recordTtl.isZero() || recordTtl.isNegative()) {
            throw new IllegalArgumentException("chat.withdrawal.record-ttl 은 양수여야 합니다: " + recordTtl);
        }
        this.redisTemplate = redisTemplate;
        this.recordTtl = recordTtl;
    }

    @Override
    public Mono<Boolean> isWithdrawing(UUID userId) {
        return redisTemplate.hasKey(ChatRedisKeys.withdrawing(userId));
    }

    @Override
    public Mono<Void> markWithdrawing(UUID userId) {
        return redisTemplate.opsForValue().set(ChatRedisKeys.withdrawing(userId), PRESENT, recordTtl).then();
    }
}
