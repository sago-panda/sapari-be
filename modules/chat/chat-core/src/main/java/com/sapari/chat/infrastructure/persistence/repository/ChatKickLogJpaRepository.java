package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.chat.infrastructure.persistence.entity.ChatKickLogEntity;

public interface ChatKickLogJpaRepository extends JpaRepository<ChatKickLogEntity, UUID> {

    /**
     * 강퇴를 기록하되 이미 있으면 아무것도 하지 않는다.
     *
     * <p><b>왜 네이티브인가</b>: 중복 강퇴는 오류가 아니라 무동작이어야 한다. JPA {@code persist}는 유니크
     * 위반을 예외로 던지므로 호출자가 "이미 있음"과 "쓰기 실패"를 구분할 수 없고, 그 구분이 밴 카운트를
     * 올릴지 말지를 정한다. {@code ON CONFLICT DO NOTHING}은 그 구분을 반환값 하나로 준다.
     *
     * <p><b>왜 {@code RETURNING id}가 아닌가</b>: 설계 문서는 {@code RETURNING id}로 적혀 있지만, 그 목적은
     * "실제로 새 행이 생겼는가"를 가르는 것이고 생성된 id를 쓰는 곳은 하류에 없다. 영향 행 수가 같은 답을
     * 더 적은 배관으로 준다 — 충돌이면 0, 삽입이면 1이다.
     *
     * <p>{@code id}는 넘기지 않는다(테이블 기본값이 생성한다). 반면 {@code kicked_at}은 기본값에 맡기지 않고
     * 명시로 넣는다 — 시각은 주입된 시계에서 와야 하고(테이블 기본값은 DB 시계다), 누적 강퇴 2년 창을
     * 고정 시계로 검증할 수 있어야 한다.
     *
     * @return 영향 행 수 — 1이면 실제 기록, 0이면 이미 있어 건너뜀
     */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.chat_kick_log
                   (user_id, live_room_id, kicked_by_id, kicked_by_role, triggering_message, kicked_at)
            VALUES (:userId, :roomId, :kickedById, :kickedByRole, :triggeringMessage, :kickedAt)
            ON CONFLICT (user_id, live_room_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("roomId") UUID roomId,
                       @Param("kickedById") UUID kickedById,
                       @Param("kickedByRole") String kickedByRole,
                       @Param("triggeringMessage") String triggeringMessage,
                       @Param("kickedAt") Instant kickedAt);
}
