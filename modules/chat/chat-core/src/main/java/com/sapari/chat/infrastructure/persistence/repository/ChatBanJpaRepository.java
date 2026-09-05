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
     * <p><b>여러 행이 살아 있을 수 있다.</b> 자동 밴이 걸린 사용자에게 관리자가 수동 밴을 더 걸 수 있고,
     * 테이블에 사용자당 하나를 강제하는 제약이 없다. 그중 아무거나 집으면 미러 TTL이 실제보다 짧아져
     * 밴이 일찍 풀린 것처럼 보인다 — 그래서 <b>가장 오래 가는 것</b>을 고른다.
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
     * 밴을 남긴다. {@code id}는 테이블 기본값이 만들고, {@code created_at}은 명시로 넣는다 —
     * 시각은 주입된 시계에서 와야 강퇴 시각과 같은 순간이 되고, 테스트가 고정 시계로 검증할 수 있다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.chat_ban (user_id, banned_by_id, expires_at, created_at)
            VALUES (:userId, :bannedById, :expiresAt, :createdAt)
            """, nativeQuery = true)
    void insert(@Param("userId") UUID userId,
                @Param("bannedById") UUID bannedById,
                @Param("expiresAt") Instant expiresAt,
                @Param("createdAt") Instant createdAt);
}
