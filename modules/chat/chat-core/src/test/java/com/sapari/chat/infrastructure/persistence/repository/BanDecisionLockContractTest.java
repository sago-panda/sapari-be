package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 밴 판단 잠금의 <b>문자열로만 표현된 계약</b>을 지킨다. 컨텍스트를 세우지 않으므로 언제 돌려도 같은
 * 답이 나온다 — {@code AutoConfigurationExclusionTest}가 자동설정 이름을 지키는 것과 같은 모양이다.
 *
 * <p>왜 필요한가: 이 계약은 네이티브 쿼리 문자열과 상수 리터럴에만 있고 <b>컴파일러가 봐 주지 않는다.</b>
 * 한 단어만 틀려도 동작은 그대로인 채 성질이 바뀌고, 그 사실은 운영에서 처음 드러난다.
 */
@DisplayName("밴 판단 잠금 — 문자열 계약")
class BanDecisionLockContractTest {

    /** 이름으로 찾는다 — 인자 목록이 바뀌어도 계약 단언이 "메서드가 사라졌다"로 오독되지 않게. */
    private static Method method(String name) {
        return java.util.Arrays.stream(ChatBanJpaRepository.class.getMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("메서드가 사라졌다: " + name));
    }

    /**
     * ⭐ {@code SET} 에서 {@code LOCAL} 이 빠지면 상한이 <b>커넥션에 남는다.</b>
     *
     * <p>{@code SET LOCAL} 은 트랜잭션과 함께 풀리지만 평문 {@code SET} 은 커밋 뒤에도 유지되고, 풀은
     * 반환 시 임의 GUC 를 되돌리지 않는다 — 그 커넥션은 수명이 끝날 때까지 이 상한을 쥔다. 그런데
     * 그 풀은 이 모듈만 쓰는 것이 아니다. 누수된 짧은 상한이 다른 도메인의 <b>정당한</b> 잠금 대기를
     * 끊으면 그쪽에는 그 예외를 잡는 코드가 없어 전역 핸들러의 마지막 그물에 걸린다.
     *
     * <p>동작은 양쪽이 같아서 어떤 기능 테스트도 이 차이를 보지 못한다(실측: {@code LOCAL} 을 지우면
     * 이 모듈 테스트가 전부 통과한다). 그래서 문자열을 직접 본다.
     */
    @Test
    @DisplayName("⭐ 대기 상한은 SET LOCAL 이다 — LOCAL 이 빠지면 커넥션에 남아 다른 도메인을 끊는다")
    void theWaitBoundIsTransactionLocal() {
        Query query = method("boundLockWait").getAnnotation(Query.class);

        assertThat(query).as("boundLockWait 에 @Query 가 없다").isNotNull();
        // 지키려는 성질은 "트랜잭션 범위"다. 접두만 보면 값이나 대상이 바뀌는 변경을 놓치므로
        // 셋을 함께 본다 — 어느 설정인지, 어떤 값인지, 그리고 그것이 트랜잭션 범위인지.
        //
        // ⚠️ set_config(..., true) 가 실측 등가이고 바인드 파라미터를 받는다(값이 리터럴이어야 하는
        // 제약이 사라진다). 그쪽으로 옮기면 이 접두 단언이 거짓 실패하므로, 그때는 "트랜잭션 범위"를
        // 재는 방식을 함께 옮길 것 — 지우지 말 것.
        assertThat(query.value())
                .as("대기 상한이 트랜잭션 범위가 아니다 — 커넥션에 남아 풀을 공유하는 다른 도메인을 끊는다")
                .startsWith("SET LOCAL ")
                .contains("lock_timeout")
                .contains(ChatBanJpaRepository.LOCK_WAIT_LIMIT);
        assertThat(query.nativeQuery()).as("SET 은 JPQL 로 해석되지 않는다").isTrue();
    }

    /**
     * ⭐ 상한 문장이 {@code @Modifying} 이어야 한다.
     *
     * <p>두 가지가 걸려 있다. 하나는 실행 방식 — {@code SET} 은 결과셋이 없어 조회로 보내면 깨진다.
     * 다른 하나가 더 중요하다: 이 애너테이션 덕분에 경계 없이 포트를 부르면
     * {@code "No active transaction for update or delete query"} 로 <b>시끄럽게 죽는다.</b>
     * 잠금 {@code SELECT} 혼자서는 그 안전망이 없어 조용히 아무것도 안 잠근다.
     */
    @Test
    @DisplayName("⭐ 상한 문장이 @Modifying 이다 — 경계 없이 부르는 것을 시끄럽게 만드는 안전망이다")
    void theWaitBoundIsAModifyingStatement() {
        assertThat(method("boundLockWait").getAnnotation(Modifying.class))
                .as("경계 없이 불러도 조용히 지나간다 — 직렬화가 사라진 것을 아무도 모른다")
                .isNotNull();
    }

    /**
     * ⭐ 잠금 키의 seed 는 <b>배포 간 같아야 한다.</b>
     *
     * <p>동어반복처럼 보이는 단언이 맞다 — 요구사항이 "바꾸려면 테스트도 같이 고치게 만든다" 하나다.
     * 값 자체에는 의미가 없지만(다른 용도와 겹치지 않기만 하면 된다) <b>같은 값인 것</b>에는 의미가
     * 있다. 롤링 배포 중에 바뀌면 구·신 버전이 서로 다른 키를 잡아 그 구간 동안 직렬화가 통째로
     * 깨지고, 증상이 "동시 강퇴에서 밴이 안 걸린다"라 배포가 끝난 뒤에는 재현되지 않는다.
     *
     * <p>"키가 그 사용자로부터 유도된다"는 다른 축이고, 그쪽은 {@code ChatKickRecorderConcurrencyTest}가
     * 잠금 범위로 지킨다. seed 를 자유 파라미터라고 적어 둔 것은 그 축에 대한 이야기였다.
     */
    @Test
    @DisplayName("⭐ 잠금 키 seed 가 고정이다 — 배포 중에 바뀌면 그 구간의 직렬화가 통째로 깨진다")
    void theLockKeySeedIsPinned() {
        assertThat(ChatBanJpaRepository.BAN_DECISION_LOCK_SEED)
                .as("seed 가 바뀌었다 — 롤링 배포 구간에서 구·신 버전이 다른 키를 잡는다")
                .isEqualTo("8317461");
        Query lockQuery = method("lockForBanDecision").getAnnotation(Query.class);
        assertThat(lockQuery).as("lockForBanDecision 에 @Query 가 없다").isNotNull();
        assertThat(lockQuery.value())
                .as("잠금 키가 그 seed 로 유도되지 않는다")
                .contains(ChatBanJpaRepository.BAN_DECISION_LOCK_SEED);
    }
}
