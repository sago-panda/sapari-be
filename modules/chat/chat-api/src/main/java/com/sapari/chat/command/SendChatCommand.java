package com.sapari.chat.command;

import java.util.UUID;

/**
 * 채팅 메시지 전송 입력.
 *
 * <p>신뢰 경계: roomId·sender*·isRoomOwner는 <b>서버가 인증 세션(ChatSession)에서</b> 채운다.
 * messageType·content·clientMsgId만 클라이언트(InboundMessage)에서 온다.
 * 핸들러는 절대 클라이언트 입력으로 sender 신원이나 isRoomOwner를 채우지 않는다(위조 방지).
 */
public record SendChatCommand(
        UUID roomId,            // 서버 신뢰 (연결 컨텍스트)
        UUID senderId,          // 서버 신뢰 (세션)
        String senderRole,      // 서버 신뢰 — ChatRole 이름(NORMAL/NOTICE 권한 판정)
        boolean isRoomOwner,    // 서버 신뢰 — NOTICE 권한(방 주인만)
        String senderNickname,  // 서버 신뢰 (발신 스냅샷)
        String senderEmail,     // 서버 신뢰 (nullable, 발신 스냅샷)
        String messageType,     // 클라 — NORMAL | NOTICE
        String content,         // 클라 — 원문(서버에서 욕설 필터링)
        String clientMsgId      // 클라 — nullable, 재전송 멱등 키
) {
    public SendChatCommand {
        // 서버가 채우는 신뢰 필드 + 클라가 보내는 타입은 누락되면 안 된다.
        // (content 길이·빈 값은 전송 흐름 입력검증 책임이라 여기서 강제하지 않음. nickname/email은 종류별 유무 달라 nullable)
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (senderId == null) {
            throw new IllegalArgumentException("senderId는 필수입니다.");
        }
        if (senderRole == null || senderRole.isBlank()) {
            throw new IllegalArgumentException("senderRole은 필수입니다.");
        }
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("messageType은 필수입니다.");
        }
    }
}
