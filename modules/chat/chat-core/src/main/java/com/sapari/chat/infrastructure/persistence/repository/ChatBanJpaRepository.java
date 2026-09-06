package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.chat.infrastructure.persistence.entity.ChatBanEntity;

public interface ChatBanJpaRepository extends JpaRepository<ChatBanEntity, UUID> {

    /**
     * 지금 유효한 밴을 만료가 먼 것부터 — 영구 밴이 가장 먼저 온다.
     *
     * <p>사용자당 행은 <b>하나뿐이다</b>({@code uk_chat_ban_user_id}). 그래서 정렬은 결과를 가르지 않는다 —
     * 그럼에도 남겨 두는 건 이 쿼리가 무엇을 고르려는 것인지가 제약에 기대지 않고 읽히게 하기 위해서다.
     * 제약이 사라지면 여러 행이 되살아나는데, 그때 아무거나 집으면 미러 TTL이 실제보다 짧아져 밴이
     * 일찍 풀린다.
     *
     * <p>{@code NULLS FIRST}는 Postgres의 {@code DESC} 기본값과 같아서 빼도 결과가 같다(되돌려 확인함).
     * 그래도 적어 두는 건 "만료 없음 = 가장 먼 만료"가 이 쿼리의 의도이지 정렬 기본값에 얹힌 우연이
     * 아니기 때문이다.
     */
    @Query(value = """
            SELECT * FROM live_schema.chat_ban
             WHERE user_id = :userId
               AND (expires_at IS NULL OR expires_at > :now)
             ORDER BY expires_at DESC NULLS FIRST
            """, nativeQuery = true)
    List<ChatBanEntity> findActive(@Param("userId") UUID userId, @Param("now") Instant now, Limit limit);

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
     * 사용자당 한 행이라 그 DELETE는 이제 남는 행 없이 완결된다(그것이 이 제약의 값어치다).
     */
    @Modifying
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
    void upsertExtending(@Param("userId") UUID userId,
                         @Param("bannedById") UUID bannedById,
                         @Param("expiresAt") Instant expiresAt,
                         @Param("createdAt") Instant createdAt);
}
