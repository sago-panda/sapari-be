package com.sapari.chat.domain.model;

import com.sapari.chat.domain.exception.ChatKickEvidenceMismatchException;

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

    /**
     * 조회한 메시지를 증거로 삼아 강퇴 기록을 만든다. <b>메시지가 정말 그 방에서 그 사람이 한 말일 때만</b>
     * 만들어진다.
     *
     * <p>이 확인이 없으면 남의 방 메시지 id 하나로 아무나 강퇴할 수 있다. 강퇴 요청이 들고 오는 것은
     * "방 · 대상 · 메시지 id" 셋인데 그 셋이 서로 맞는지는 아무도 보장해 주지 않는다 — 방과 대상은 요청자가
     * 정하고, 메시지 id도 요청자가 정한다. 셋을 맞춰 보는 곳이 여기 하나뿐이라 여기서 막는다.
     *
     * <p>맞지 않으면 <b>거부한다</b>. 원문 자리를 "(알 수 없음)" 같은 값으로 채워 강퇴를 진행시키지 않는다 —
     * 그렇게 남긴 행은 나중에 진짜 증거와 구분되지 않고, 그 행 하나가 누적 강퇴를 올려 밴까지 밀어 올린다.
     *
     * <p>실패는 {@link ChatKickEvidenceMismatchException}(4xx)이다. 세 값을 전부 요청자가 정하므로
     * 어긋나는 것은 서버 오류가 아니라 정상적으로 일어나는 거부다. 어긋난 축은 로그로만 가고 응답
     * 문구는 세 갈래가 모두 같다 — 어디까지 맞았는지를 알려주면 방·메시지 id를 탐색하는 통로가 된다.
     *
     * @param evidence  {@code messageId}로 조회한 메시지 조각
     * @param roomId    강퇴가 일어나는 방
     * @param targetUserId 강퇴 대상
     * @param kickedById   강퇴를 실행한 사람
     * @param kickedByRole 그 사람의 역할(SELLER·ADMIN)
     * @param kickedAt     주입된 시계에서 온 시각 — 누적 강퇴 2년 창이 이 값으로 계산된다
     */
    public static ChatKickLog from(ChatMessageEvidence evidence, UUID roomId, UUID targetUserId,
                                   UUID kickedById, ChatRole kickedByRole, Instant kickedAt) {
        if (evidence == null) {
            throw new ChatKickEvidenceMismatchException("증거 메시지가 없다 — roomId=" + roomId);
        }
        if (!evidence.roomId().equals(roomId)) {
            throw new ChatKickEvidenceMismatchException(
                    "증거 메시지가 다른 방의 것이다 — 요청=" + roomId + " 메시지=" + evidence.roomId());
        }
        if (!evidence.senderId().equals(targetUserId)) {
            throw new ChatKickEvidenceMismatchException(
                    "증거 메시지의 작성자가 강퇴 대상이 아니다 — 대상=" + targetUserId
                            + " 작성자=" + evidence.senderId());
        }
        return new ChatKickLog(targetUserId, roomId, kickedById, kickedByRole,
                evidence.originalMessage(), kickedAt);
    }
}
