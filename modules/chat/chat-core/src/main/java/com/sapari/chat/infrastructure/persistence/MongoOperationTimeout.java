package com.sapari.chat.infrastructure.persistence;

import com.mongodb.MongoOperationTimeoutException;

/**
 * 연산 상한이 끊은 것인지 판정한다.
 *
 * <p><b>왜 infrastructure 인가.</b> 판정에 드라이버 타입이 필요하고, 루트 {@code AGENTS.md} 가
 * "never call an SDK/JPA directly from a service" 로 못 박는다. 형제
 * {@code infrastructure/redis/RedisWrongType} 가 같은 일(감싸인 예외를 사슬로 판정)을 같은 자리에서
 * 같은 모양으로 한다 — 그 선례를 따른다.
 *
 * <p><b>사슬을 걸어야 하는 이유.</b> CSOT 가 자를 때 실제로 오는 체인이다(실측, 드라이버 5.6.5):
 * <pre>
 * [0] org.springframework.data.mongodb.UncategorizedMongoDbException   ← Spring 전용 번역이 없다
 * [1] com.mongodb.MongoOperationTimeoutException                       ← 판정 지점
 * [2] com.mongodb.MongoSocketReadTimeoutException
 * [3] java.nio.channels.InterruptedByTimeoutException
 * </pre>
 * 최상위만 보면 전용 타입이 아니라 못 가른다. 반대로
 * {@code NestedExceptionUtils.getMostSpecificCause} 는 <b>가장 깊은 노드</b>를 주므로 깊이 3 의
 * {@code InterruptedByTimeoutException} 이 나오고, 상한을 "입출력 오류" 로 분류하게 된다.
 */
public final class MongoOperationTimeout {

    private MongoOperationTimeout() {
    }

    /** 감싸인 예외라 원인 사슬을 따라 내려가며 본다. cause 가 자기 자신인 경우를 대비해 끊는다. */
    public static boolean matches(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoOperationTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
