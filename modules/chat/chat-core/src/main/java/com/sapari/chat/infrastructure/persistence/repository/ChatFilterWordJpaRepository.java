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
     * 그 종류의 단어만 읽는다 — 엔티티를 띄우지 않는다.
     *
     * <p>이 질의의 호출자는 스냅샷 발행이고 그것이 쓰는 값은 단어 문자열뿐이다. 엔티티로 읽으면
     * 항목 수만큼 객체가 뜨고 {@code id}·{@code created_by}·{@code created_at} 가 함께 올라오는데,
     * 그 셋을 읽는 코드가 하류에 없다. 사전 규모가 1,078건이라 지금도 의미가 있고, 이 질의는
     * 사전이 바뀔 때마다 돈다.
     */
    @Query("SELECT e.word FROM ChatFilterWordEntity e WHERE e.kind = :kind")
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
