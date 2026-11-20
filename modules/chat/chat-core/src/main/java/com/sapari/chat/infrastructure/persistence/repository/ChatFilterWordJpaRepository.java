package com.sapari.chat.infrastructure.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.chat.infrastructure.persistence.entity.ChatFilterWordEntity;

public interface ChatFilterWordJpaRepository extends JpaRepository<ChatFilterWordEntity, UUID> {

    /**
     * 사전 쓰기 잠금의 seed — 밴 판정 잠금({@code ChatBanJpaRepository.BAN_DECISION_LOCK_SEED})과
     * 키 공간을 가른다. 그쪽 javadoc 이 seed 로 네임스페이스를 얻는 이유를 적는다.
     */
    String DICTIONARY_WRITE_LOCK_SEED = "5204913";

    /**
     * 사전 쓰기를 직렬화한다 — 트랜잭션이 끝날 때 풀리는 어드바이저리 잠금 하나(사전 전체).
     *
     * <p>반환형이 {@code void} 이고 {@code @Modifying} 이 없는 이유는
     * {@code ChatBanJpaRepository.lockForBanDecision} 과 같다.
     */
    @Query(value = "SELECT pg_advisory_xact_lock("
            + "hashtextextended('chat_filter_word', " + DICTIONARY_WRITE_LOCK_SEED + "))",
            nativeQuery = true)
    void lockForDictionaryWrite();

    /**
     * 그 종류의 단어만 읽는다 — 엔티티를 띄우지 않는다.
     *
     * <p>이 질의의 호출자는 스냅샷 발행이고 그것이 쓰는 값은 단어 문자열뿐이다. 엔티티로 읽으면
     * 항목 수만큼 객체가 뜨고 {@code id}·{@code created_by}·{@code created_at} 가 함께 올라오는데,
     * 그 셋을 읽는 코드가 하류에 없다. 사전 규모가 1,078건이라 지금도 의미가 있고, 이 질의는
     * 사전이 바뀔 때마다 돈다.
     *
     * <p><b>{@code ORDER BY} 가 있어야 하는 이유.</b> 호출자가 순서를 보존하는 집합으로 받으면서
     * 근거를 "두 Pod 의 출력을 눈으로 비교할 수 있게" 로 적었는데, 정렬이 없으면 Postgres 는
     * 순서를 보장하지 않으므로 그 근거가 실현되지 않는다. 그리고 그 순서가 스냅샷 본문의 줄
     * 순서가 되므로, 정렬이 없으면 <b>내용이 같은 스냅샷이 매번 다른 바이트</b>가 된다 —
     * 왕복 비교와 진단이 둘 다 흔들린다.
     *
     * <p>비용은 0 에 가깝다. 이 질의는 사전이 바뀔 때만 돌고(사람 타이핑 빈도) 정렬 대상이
     * 1,000여 건이다.
     *
     * <p>⚠️ <b>자바의 {@code String} 순서가 아니다.</b> Postgres 콜레이션이 한글을 다르게
     * 정렬한다(실측, postgres:16.15 컨테이너: 지랄·개새끼·시발 → {@code 시발, 지랄, 개새끼}.
     * 자바 {@code Collections.sort} 는 {@code 개새끼, 시발, 지랄}). 필요한 성질은 "어떤 순서" 가
     * 아니라 <b>한 DB 안에서 같은 입력이 같은 순서</b>이므로 그 차이는 무해하다 — 그래서
     * 테스트도 결정론만 잰다. 시드의 정렬(자바)과 이 정렬(DB)이 다른 것도 같은 이유로
     * 무해하다: 시드 쪽은 모든 Pod 이 같은 순서로 행 락을 잡게 하려는 것이고 이쪽은 읽은
     * 결과가 흔들리지 않게 하려는 것이다.
     */
    @Query("SELECT e.word FROM ChatFilterWordEntity e WHERE e.kind = :kind ORDER BY e.word")
    List<String> findWordsByKind(@Param("kind") String kind);

    /**
     * 항목을 더하되 이미 있으면 아무것도 하지 않는다.
     *
     * <p><b>왜 네이티브인가</b>: 같은 단어를 두 번 추가하는 것은 오류가 아니라 무동작이어야 한다.
     * JPA {@code persist} 는 {@code uk_chat_filter_word (kind, word)} 위반을 예외로 던져
     * "이미 막혀 있다" 와 "쓰기가 실패했다" 를 호출자가 구분할 수 없게 만든다 —
     * {@code ON CONFLICT DO NOTHING} 은 그 구분을 영향 행 수 하나로 준다.
     *
     * <p>그리고 그 멱등성이 <b>시드를 여러 Pod 가 동시에 해도 안전하게</b> 만든다. live-app 이
     * 여러 개면 전부 "테이블이 비었다" 를 보고 동시에 시드를 밀어 넣는데, 충돌이 무동작이라
     * 결과가 같다. 락을 두지 않은 근거가 이것이다.
     *
     * <p>{@code id}·{@code created_at} 은 넘기지 않는다 — 테이블 기본값
     * ({@code gen_random_uuid()} · {@code now()})이 만든다. {@code ChatKickLogEntity} 쪽은
     * {@code kicked_at} 을 주입된 시계에서 명시로 넣는데, 그건 그 값에서 <b>2년 누적 창</b>을
     * 역산하기 때문이다. 여기 {@code created_at} 은 감사용이고 그것으로 계산하는 규칙이 없어서
     * 시계를 끌고 오지 않는다.
     *
     * @return 영향 행 수 — 1이면 실제 추가, 0이면 이미 있어 건너뜀
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.chat_filter_word (kind, word, created_by)
            VALUES (:kind, :word, :createdBy)
            ON CONFLICT (kind, word) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("kind") String kind,
                       @Param("word") String word,
                       @Param("createdBy") UUID createdBy);

    /**
     * 항목을 뺀다.
     *
     * @return 영향 행 수 — 1이면 지워짐, 0이면 없었음. 유니크 제약이 {@code (kind, word)} 라
     *         1을 넘을 수 없다
     */
    @Modifying
    @Query(value = """
            DELETE FROM live_schema.chat_filter_word
             WHERE kind = :kind AND word = :word
            """, nativeQuery = true)
    int deleteByKindAndWord(@Param("kind") String kind, @Param("word") String word);
}
