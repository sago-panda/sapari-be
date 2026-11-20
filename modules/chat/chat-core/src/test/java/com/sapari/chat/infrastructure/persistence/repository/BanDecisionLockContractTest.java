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
                // ⚠️ 상수를 넣으면 동어반복이다 — 쿼리 문자열이 그 상수로 조립되므로 값을
                // 바꿀 때 양쪽이 같이 바뀌어 아무것도 안 걸린다. 리뷰어 실측: 상수를 "0"으로 바꿔도
                // 통과했고, Postgres 에서 lock_timeout='0' 은 상한 해제(영구 대기)라
                // boundLockWait 가 막으려는 바로 그 상태다. 리터럴로 적어 "바꾸려면 여기도 고치게"
                // 만든다 — seed 단언과 같은 이유다.
                .contains("'1s'");
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

    /**
     * ⭐ <b>해제도 같은 잠금을 잡는다</b> — 안 잡으면 접은 누적이 레이스로 되살아난다.
     *
     * <p>{@code ChatKickRecorder.record}는 세기와 쓰기를 둘 다 이 잠금 안에 두고 커밋까지 쥔다.
     * 해제가 잠금 없이 {@code chat_ban}·{@code chat_ban_release}만 건드리면 <b>어느 자원에서도
     * 충돌하지 않고</b> 그 사이에 끼어든다:
     * <pre>
     *   T1(강퇴)  잠금 → append → findActive(없음) → 세기(기준선 없음 → 9명)
     *   R(해제)                            ├─ DELETE + 기준선 INSERT, 커밋
     *   T1                                 └→ extendOrCreate(ONE_YEAR), 커밋
     * </pre>
     * 결과는 운영자가 방금 접은 누적으로 <b>해제 직후 1년 밴</b>이다. 사면 기준선을 들인 이유인
     * 그 구멍이 레이스로 다시 열리는 것이라 심각도가 낮지 않다.
     *
     * <p><b>레이스를 재현하지 않는다.</b> 이 파일의 다른 단언과 같은 이유다 — 경쟁 테스트는 도구에
     * 시간을 쓰게 하고 그 결과가 환경에 딸려간다. 여기서 재는 것은 <b>계약</b>이다: 해제 경로가
     * 잠금을 잡는 호출이 소스에 있는가. 잠금을 떼면 이 한 건이 깨진다.
     *
     * <p>자원 순서도 같이 본다. 잠금 호출이 <b>release 호출보다 먼저</b>여야 전순서가 유지된다 —
     * 강퇴는 {@code 어드바이저리 → chat_kick_log → chat_ban}, 해제는
     * {@code 어드바이저리 → chat_ban → chat_ban_release}이고, 어드바이저리가 맨 앞이라 사이클이
     * 없다. 뒤로 옮기면 그 보장이 사라지고 운영에서 {@code 40P01}로 처음 드러난다.
     */
    @Test
    @DisplayName("⭐ 밴 해제가 밴 판단 잠금을 먼저 잡는다 — 떼면 접은 누적이 되살아난다")
    void releaseTakesTheBanDecisionLockFirst() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/sapari/chat/application/service/ChatBanReleaseRecorder.java"));
        // 앵커를 반환형에 묶지 않는다 — 처음에 "public boolean release(" 로 잡았는데 반환형이
        // Released 로 바뀌면서 indexOf 가 -1 이 되어 이 테스트가 엉뚱한 이유로 깨졌다.
        int at = source.indexOf(" release(UUID targetUserId");
        assertThat(at).as("release 메서드를 못 찾았다 — 시그니처가 바뀌면 앵커도 옮길 것").isNotNegative();
        String body = source.substring(at);

        int lockAt = body.indexOf("lockForBanDecision(");
        int releaseAt = body.indexOf("banStateRepository.release(");

        assertThat(lockAt)
                .as("해제가 밴 판단 잠금을 잡지 않는다 — 강퇴의 세기와 쓰기 사이에 끼어들 수 있다")
                .isNotNegative();
        assertThat(lockAt)
                .as("잠금이 쓰기보다 뒤다 — 어드바이저리가 맨 앞이어야 두 경로의 자원 순서가 유지된다")
                .isLessThan(releaseAt);
    }
}
