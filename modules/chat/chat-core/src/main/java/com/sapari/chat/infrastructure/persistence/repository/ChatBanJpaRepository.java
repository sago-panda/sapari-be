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
    int upsertExtending(@Param("userId") UUID userId,
                        @Param("bannedById") UUID bannedById,
                        @Param("expiresAt") Instant expiresAt,
                        @Param("createdAt") Instant createdAt);
}
