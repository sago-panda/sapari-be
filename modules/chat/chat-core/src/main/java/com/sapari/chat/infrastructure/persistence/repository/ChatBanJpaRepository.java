package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.chat.infrastructure.persistence.entity.ChatBanEntity;

public interface ChatBanJpaRepository extends JpaRepository<ChatBanEntity, UUID> {

    /**
     * 밴 판단 잠금의 해시 seed — 이 잠금이 쓰는 키 공간의 이름이다.
     *
     * <p>어드바이저리 잠금 키 공간은 DB 전역에 하나라 다른 용도와 겹칠 수 있다. seed가 그 공간을
     * 나눈다 — 값 자체에 의미는 없고 <b>다른 용도와 다르다는 것</b>만이 의미다.
     *
     * <p>⚠️ 바꾸지 마라. 바꾸는 순간 롤링 배포 중의 구·신 버전이 서로 다른 키를 잡아 그 구간 동안
     * 직렬화가 통째로 깨진다 — 그리고 깨진 증상은 "동시 강퇴에서 밴이 안 걸린다"라서, 배포가 끝난
     * 뒤에는 재현되지 않는다.
     *
     * <p>{@code @Query}가 컴파일 상수를 요구하므로 문자열이고, 쓰이는 곳보다 먼저 선언돼야 한다.
     */
    String BAN_DECISION_LOCK_SEED = "8317461";

    /** 잠금 대기 상한. 정상 보유는 밀리초라 정당한 동시 강퇴는 여기 닿지 않는다. */
    String LOCK_WAIT_LIMIT = "1s";

    /**
     * 이 트랜잭션의 <b>잠금 대기</b> 상한을 건다. 잠금을 잡기 직전에 불러야 한다.
     *
     * <p><b>왜 트랜잭션 타임아웃으로는 부족한가.</b> 강퇴는 인증된 엔드포인트지만 레이트리밋이 없다 —
     * 판매자 계정 하나와 자기 방에서 발화한 계정 하나만 있으면 같은 요청을 동시에 K개 던질 수 있다.
     * 전부 같은 키에 줄을 서고, <b>기다리는 동안에도 각자 커넥션을 쥔다</b>(잠금이 트랜잭션 첫 문이라
     * 커넥션이 이미 획득된 상태다). 그러면 커넥션 보유 시간이 '작업 시간(밀리초)'에서 '대기열 깊이 ×
     * 작업 시간, 상한은 트랜잭션 타임아웃'으로 바뀌고, 그 상한이 5초라 풀이 오래 마른다. 이 저장소에
     * Hikari 설정이 없어 기본값(10)이면 동시 10건으로 풀이 포화된다.
     *
     * <p>이 결합은 어드바이저리 잠금이 들어오면서 <b>새로 생겼다.</b> 그 전에 같은 (사용자, 방)으로
     * 반복 강퇴하면 아무것도 기다리지 않았다 — 커밋된 충돌 행에 대한 {@code ON CONFLICT DO NOTHING}은
     * 잠금 대기 없이 끝난다(리뷰어 실측 0.539ms).
     *
     * <p>상한을 걸면 대기열이 <b>빨리 실패</b>하고, 커넥션 보유가 대기열 깊이와 무관해진다. 실패는
     * 이미 있는 경로로 흘러간다 — 호출자가 경합 예외로 번역해 409로 답하고, 클라는 재시도한다.
     * 정상 보유 시간이 밀리초라 정당한 동시 강퇴(다른 방, 같은 사람)는 이 상한에 닿지 않는다.
     *
     * <p>{@code statement_timeout}이 아니라 {@code lock_timeout}인 것이 중요하다 — 잠금 대기만 끊고
     * 집계 쿼리의 실행 시간에는 손대지 않는다. {@code SET LOCAL}이라 트랜잭션과 함께 풀린다.
     *
     * <p>값은 리터럴이다({@code SET}은 파라미터 바인딩을 받지 않는다). 그래서
     * {@link #LOCK_WAIT_LIMIT}이 상수이고, 이 값은 <b>테스트의 랑데부 상한보다 커야 한다</b> —
     * 그 테스트가 한쪽을 일부러 잠금에서 기다리게 만들기 때문이다.
     */
    @Modifying
    @Query(value = "SET LOCAL lock_timeout = '" + LOCK_WAIT_LIMIT + "'", nativeQuery = true)
    void boundLockWait();

    /**
     * 사용자 단위 어드바이저리 잠금 — 밴 판단(세기 → 견주기 → 쓰기)을 직렬화한다.
     *
     * <p><b>왜 행 잠금이 아닌가.</b> 잠글 행이 없을 수 있다. 밴이 처음 걸리는 순간이 바로 이 직렬화가
     * 필요한 순간이고, 그때 {@code chat_ban}에는 이 사용자의 행이 아직 없다. {@code SELECT FOR UPDATE}는
     * 없는 행을 잠그지 못하므로(그래서 이 결함이 지금까지 남아 있었다) 행과 무관한 잠금이 필요하다.
     *
     * <p><b>왜 {@code SERIALIZABLE}이 아닌가.</b> 그쪽은 직렬화 실패를 예외로 돌려주므로 강퇴 흐름 전체에
     * 재시도 책임이 생긴다 — 이미 Redis 쓰기와 발행이 커밋 뒤에 줄줄이 붙어 있어 재시도 단위를 정하는
     * 일이 작지 않다. 잠금은 기다리기만 하고, 그 대기의 상한은 <b>바로 위 {@link #boundLockWait}가
     * 쥔다</b> — 호출자의 트랜잭션 타임아웃이 아니다. 전에는 그렇게 적혀 있었는데, 대기하는 쪽도
     * 커넥션을 쥐므로 트랜잭션 상한(초 단위)으로는 너무 늦다는 것을 알게 되어 상한을 따로 들였다.
     * 이 문단을 근거로 {@code boundLockWait}를 "중복이니 제거"로 읽지 말 것.
     *
     * <p>{@code _xact_} 변종이라 <b>커밋·롤백과 함께 자동으로 풀린다</b> — 명시적 해제 경로가 없어서
     * 예외로 빠져나가도 잠금이 남지 않는다. 세션 단위 변종({@code pg_advisory_lock})을 쓰면 커넥션 풀이
     * 그 커넥션을 재사용하는 순간 잠금이 엉뚱한 요청에 딸려간다.
     *
     * <p><b>키는 64비트로 만든다.</b> {@code hashtext}는 int4라 수십만 사용자 규모에서도 충돌이 흔하고
     * (생일 문제), 충돌하면 서로 무관한 두 사용자가 줄을 선다 — 정확성 손실은 아니고 성능 비용이지만
     * 공짜로 피할 수 있다. {@code hashtextextended}는 int8을 돌려주므로 실질적으로 충돌이 없다.
     *
     * <p>어드바이저리 잠금 키 공간은 <b>DB 전역에 하나</b>라 다른 모듈이 쓰기 시작하면 이름이 겹칠 수
     * 있다. 그래서 seed를 비워 두지 않고 {@link #BAN_DECISION_LOCK_SEED}를 준다 — 같은 사용자 문자열이라도
     * seed가 다르면 키가 갈리므로, 네임스페이싱이 나중의 이전 작업이 아니라 지금의 상수 하나로 끝난다
     * (실측: {@code hashtextextended('abc',0)}과 {@code hashtextextended('abc',7777777)}은 다르고, 같은
     * seed는 늘 같은 값을 준다).
     *
     * <p>⚠️ <b>2-인자 변종({@code pg_advisory_xact_lock(int4, int4)})으로 옮기지 마라.</b> 한때 이 자리에
     * "그때는 2-인자로 옮겨야 한다"고 적혀 있었는데 틀렸다. 그 변종은 두 인자가 <b>각각 32비트</b>라
     * (실측: 오버로드는 {@code bigint}와 {@code integer, integer} 둘뿐이다) 한쪽을 용도 접두로 쓰면
     * 사용자 키가 int4로 내려간다 — 바로 위 단락이 {@code hashtext}를 기각한 그 생일 문제가 그대로
     * 돌아온다. 64비트를 지키면서 네임스페이스를 얻는 길은 seed뿐이다.
     *
     * <p>반환형이 {@code void}인 것은 <b>Spring Data가 비-{@code @Modifying} 쿼리의 단일 결과를 버리기</b>
     * 때문이다. 이 SELECT 자체는 1행 1열을 돌려준다 — 함수가 {@code void} 타입이라서가 아니다(그렇게
     * 적혀 있었는데 인과가 성립하지 않는다. {@code pg_try_advisory_xact_lock}처럼 boolean을 돌려주는
     * 함수로 바꿔도 {@code void} 선언이 그대로 동작한다). 쓰기가 아니라 잠금 획득이라
     * {@code @Modifying}을 붙이지 않는다 — 붙이면 {@code executeUpdate()}로 불려 SELECT에서 깨진다.
     */
    @Query(value = "SELECT pg_advisory_xact_lock("
            + "hashtextextended(cast(:userId as text), " + BAN_DECISION_LOCK_SEED + "))",
            nativeQuery = true)
    void lockForBanDecision(@Param("userId") UUID userId);

    /**
     * 지금 유효한 밴. 만료가 없거나({@code expires_at IS NULL}) 아직 지나지 않은 행이다.
     *
     * <p>결과는 <b>많아야 하나</b>다 — {@code uk_chat_ban_user_id}가 사용자당 한 행을 보장한다.
     *
     * <p>전에는 "가장 오래 가는 것"을 고르려고 {@code ORDER BY expires_at DESC NULLS FIRST}와
     * {@code LIMIT 1}이 붙어 있었다. <b>뺐다.</b> 제약이 선 뒤로는 정렬이 결과를 가를 수 없어서 어떤
     * 테스트도 그 정렬에 닿지 못하는데(뒤집어도 스위트가 전부 통과한다 — 리뷰어 실측), 그러면 "제약이
     * 사라지는 날의 안전망"이라는 주장만 남고 그 주장을 지키는 것이 없다. 검증할 수 없는 방어는 방어가
     * 아니라 다음 사람이 믿게 되는 문장이다. 제약을 되돌린다면 그 변경이 정렬과 그 정렬을 재는 테스트를
     * 함께 가져와야 한다.
     */
    @Query(value = """
            SELECT * FROM live_schema.chat_ban
             WHERE user_id = :userId
               AND (expires_at IS NULL OR expires_at > :now)
            """, nativeQuery = true)
    Optional<ChatBanEntity> findActive(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * 밴을 <b>늘리는 방향으로만</b> 남긴다. {@code id}는 테이블 기본값이 만들고, {@code created_at}은
     * 명시로 넣는다 — 시각은 주입된 시계에서 와야 강퇴 시각과 같은 순간이 되고, 테스트가 고정 시계로
     * 검증할 수 있다.
     *
     * <p><b>왜 upsert인가.</b> 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출이 각각 "활성 밴 없음"을
     * 읽고 각각 쓴다 — READ COMMITTED라 서로의 미커밋 행이 보이지 않아, 트랜잭션을 열어도 막히지 않는다.
     * 막는 것은 격리 수준이 아니라 유니크 제약이고, 제약이 서면 두 번째 쓰기는 실패가 아니라 갱신이 된다.
     *
     * <p><b>왜 늘리기만 하는가.</b> 둘 중 늦게 도착한 쪽이 짧을 수 있는데 무조건 덮으면 그 사람은 정본에
     * 한 달이 남아 있어도 일주일 뒤에 돌아온다. 미러({@code chat:banned:}) 쪽에서 같은 이유로 이미
     * 단조 규칙을 쓰고 있고, 정본이 다른 규칙을 쓰면 둘의 만료가 갈린다. {@code expires_at IS NULL}은
     * 영구라서 가장 긴 만료이고, 그래서 어떤 값도 그것을 밀어내지 못한다.
     *
     * <p>⚠️ <b>이 쿼리로는 밴을 짧게 줄일 수 없다.</b> 관리자 감형은 행 DELETE 후 재삽입이어야 한다 —
     * 사용자당 한 행이라 그 DELETE는 <b>이 테이블 안에서는</b> 남는 행 없이 완결된다. 다만 집행은 이
     * 테이블이 아니라 미러가 한다({@code EntryGate}·{@code SendChatService}가 {@code chat:banned:}만
     * 본다). 정본만 지우면 화면에는 "해제됨"인데 사용자는 계속 못 들어오고, 정본이 비어 원인 추적은
     * 오히려 더 어려워진다. <b>해제는 미러 삭제까지가 한 단위다.</b>
     *
     * <p><b>0행을 돌려줄 수 있다.</b> 이미 더 긴 밴이 있어 아무것도 바뀌지 않은 경우다 — 실패가 아니다.
     * 호출자가 이 값을 무시하면 "DB에 없는 밴"을 걸었다고 기록하게 된다.
     *
     * <p>⚠️ <b>수동 밴은 이 쿼리를 타면 안 된다.</b> 충돌 시 {@code banned_by_id}와 {@code created_at}까지
     * 덮으므로, 관리자가 건 기한부 밴 위에 자동 승격이 한 번 오면 발급자가 {@code SYSTEM}으로 바뀌고 그
     * 관리자 조치는 어디에도 남지 않는다({@code chat_kick_log}는 강퇴만 남긴다). 영구 밴만
     * {@code existing.expires_at IS NOT NULL} 가드가 지킨다. 관리자 경로를 만들 때 그 보존 결정
     * (이력 행을 두거나, 충돌 시 그 두 열을 건드리지 않거나)을 <b>같은 변경에</b> 함께 내려야 한다 —
     * 잃을 밴이 생긴 뒤에 정하면 이미 덮인 것은 복구되지 않는다.
     */
    // clearAutomatically — 네이티브 쿼리라 영속성 컨텍스트가 이 갱신을 모른다. 0행 분기에서 같은
    // 컨텍스트로 다시 읽으면 1차 캐시의 갱신 전 값이 돌아올 수 있다. 오늘은 호출자가 먼저 읽어
    // 있으면 여기까지 오지 않아 도달하지 않지만, 그 전제가 깨지는 순간 조용히 틀린 만료를 미러에 싣는다.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO live_schema.chat_ban AS existing (user_id, banned_by_id, expires_at, created_at)
            VALUES (:userId, :bannedById, :expiresAt, :createdAt)
            ON CONFLICT (user_id) DO UPDATE
               SET expires_at   = EXCLUDED.expires_at,
                   banned_by_id = EXCLUDED.banned_by_id,
                   created_at   = EXCLUDED.created_at
             WHERE existing.expires_at IS NOT NULL
               AND (EXCLUDED.expires_at IS NULL
                    OR EXCLUDED.expires_at > existing.expires_at)
            """, nativeQuery = true)
    int upsertExtending(@Param("userId") UUID userId,
                        @Param("bannedById") UUID bannedById,
                        @Param("expiresAt") Instant expiresAt,
                        @Param("createdAt") Instant createdAt);

    /**
     * 이 사용자의 밴 행을 지운다 — 만료 여부를 묻지 않는다.
     *
     * <p>만료를 조건에 넣지 않는 이유: 만료된 행이 남아 있어도 다음 자동 승격의
     * {@code ON CONFLICT} 대상이 되고, 거기서 {@code banned_by_id}·{@code created_at}이 덮인다.
     * 해제를 누른 운영자의 뜻은 "이 사람의 현재 제재를 없앤다" 이므로 사용자당 한 행 전체가 대상이다
     * ({@code uk_chat_ban_user_id}가 많아야 하나를 보장한다).
     *
     * @return 지운 행 수 — 0 또는 1. 0 은 실패가 아니다(멱등)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM live_schema.chat_ban WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * 사면 기준선을 남긴다 — append-only.
     *
     * <p>{@code chat_ban} 행을 지우는 것만으로는 해제가 완결되지 않는다. 강퇴 로그가 남아 있어
     * 새 방 강퇴 1회로 같은 티어가 재적용되기 때문이고, 그 재적용을 막는 것이 이 행이다
     * ({@code ChatKickLogJpaRepository.countConfirmingKickersSince}의 상관 서브쿼리가 읽는다).
     *
     * <p>{@code id}는 테이블 기본값이 만들고 {@code released_at}은 명시로 넣는다 — 시각이 주입된
     * 시계에서 와야 테스트가 고정 시계로 기준선 효과를 검증할 수 있다.
     *
     * <p>지울 행이 없었어도 이 행은 남긴다. 만료가 이미 지난 사용자에게 해제를 누르는 것도
     * "누적을 접는다" 는 뜻이고, 그 뜻을 버리면 운영자가 같은 버튼을 두 번 눌러도 누적이 안 접힌다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.chat_ban_release (user_id, released_by_id, released_at)
            VALUES (:userId, :releasedById, :releasedAt)
            """, nativeQuery = true)
    void appendRelease(@Param("userId") UUID userId,
                       @Param("releasedById") UUID releasedById,
                       @Param("releasedAt") Instant releasedAt);
}
