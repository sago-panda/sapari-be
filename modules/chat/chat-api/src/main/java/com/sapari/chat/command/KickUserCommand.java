package com.sapari.chat.command;

import java.util.UUID;

/**
 * 강퇴 명령 (live-app REST). <b>커맨드는 신원만 담는다 — 역할도 방 주인 여부도 담지 않는다.</b>
 * 둘 다 서버가 직접 로드해 판정한다. 클라이언트가 보낸 역할을 믿으면 구매자가 자기를 ADMIN이라
 * 적어 아무나 강퇴할 수 있다.
 *
 * <p><b>본문이 아니라 {@code messageId}를 받는다.</b> 강퇴 증거로 남길 원문은 서버가 그 id로 직접 읽어
 * 박는다 — 클라이언트가 본문을 실어 보내면 무엇 때문에 끊었는지를 강퇴한 쪽이 지어낼 수 있고, 그러면
 * 증거가 증거가 아니게 된다. 설계 문서는 커맨드에 {@code triggeringMessage}(원문)를 두라고 적었지만,
 * 그 목적이 "분쟁 대비 self-contained 증거"라 조작 가능한 경로로는 목적을 이루지 못한다.
 *
 * @param messageId 강퇴 사유가 된 메시지의 서버 id(Mongo ObjectId). 형식 검증은 조회 어댑터의 몫이라
 *                  여기서는 비어 있지 않은지만 본다 — 계약(chat-api)이 저장소 타입을 알면 안 된다.
 */
public record KickUserCommand(
        UUID roomId,
        UUID kickerId,
        UUID targetUserId,
        String messageId
) {
    public KickUserCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (kickerId == null) {
            throw new IllegalArgumentException("kickerId는 필수입니다.");
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId는 필수입니다.");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId는 필수입니다 — 증거 없는 강퇴는 받지 않는다.");
        }
    }
}
