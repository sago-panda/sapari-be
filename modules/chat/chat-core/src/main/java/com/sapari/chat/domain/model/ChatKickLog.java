package com.sapari.chat.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 강퇴 1건의 증거. append-only라 수정도 해제도 없다 — 밴은 별도 상태({@code chat_ban})가 들고 있다.
 *
 * <p><b>{@code triggeringMessage}는 원문 스냅샷이다.</b> 메시지 id를 참조로 두지 않고 내용을 복사해 박는다.
 * 참조로 두면 메시지가 TTL로 사라지거나 마스킹 정책이 바뀐 뒤에 증거가 같이 사라지는데, 분쟁은 대개 그
 * 뒤에 온다. 마스킹 <b>전</b> 원문이어야 하는 이유도 같다 — 무엇 때문에 끊었는지가 증거의 전부다.
 *
 * <p>같은 방·같은 유저는 한 번만 기록된다({@code UNIQUE(user_id, live_room_id)}). 중복 요청이 밴 카운트를
 * 부풀리지 않게 하려는 것이라, 재시도가 몇 번 오든 이 유저의 누적 강퇴는 방 하나당 1이다.
 */
public record ChatKickLog(
        UUID targetUserId,
        UUID roomId,
        UUID kickedById,
        ChatRole kickedByRole,
        String triggeringMessage,
        Instant kickedAt
) {
    public ChatKickLog {
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId는 필수입니다.");
        }
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (kickedById == null) {
            throw new IllegalArgumentException("kickedById는 필수입니다.");
        }
        // 테이블에 CHECK(kicked_by_role IN ('SELLER','ADMIN'))가 걸려 있다. 여기서 막지 않으면 위반이
        // 제약 위반 예외로만 드러나 어느 값이 문제인지 알기 어렵고, 그 시점엔 이미 트랜잭션 안이다.
        if (kickedByRole != ChatRole.SELLER && kickedByRole != ChatRole.ADMIN) {
            throw new IllegalArgumentException("강퇴할 수 있는 역할은 SELLER·ADMIN뿐입니다: " + kickedByRole);
        }
        if (triggeringMessage == null || triggeringMessage.isBlank()) {
            throw new IllegalArgumentException("triggeringMessage는 필수입니다 — 증거 없는 강퇴는 남기지 않는다.");
        }
        if (kickedAt == null) {
            throw new IllegalArgumentException("kickedAt은 필수입니다.");
        }
    }
}
