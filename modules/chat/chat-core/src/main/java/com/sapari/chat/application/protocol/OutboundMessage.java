package com.sapari.chat.application.protocol;

import java.time.Instant;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatRole;

/**
 * 서버 → 클라이언트 렌더 메시지. chat-core 소유(senderRole이 ChatRole(도메인)이라 chat-api 불가).
 *
 * <p>필드 유무는 {@code type}에 따라 달라진다(아래 주석). ChatSessionManager.sendToSession 파라미터이며,
 * 생산은 SendChatService 에코 폴백·SystemMessageService 렌더·ChatBroadcastSubscriber가 맡는다.
 */
public record OutboundMessage(
        String type,             // NORMAL | NOTICE | SYSTEM | KICK | ROOM_INFO | RATE_LIMIT | ERROR | ACK
        String code,             // SYSTEM: KICKED|ROOM_ENDED|BANNED / ERROR: VALIDATION|PERMISSION|KICKED|NOT_ACTIVE|INTERNAL (그 외 null)
        String id,               // NORMAL/NOTICE: MongoDB ObjectId / ACK: 저장된 serverId (그 외 null)
        UUID senderId,           // NORMAL/NOTICE: 실제 발신자 / SYSTEM: 고정 시스템 UUID / 그 외 null
        String senderNickname,   // NORMAL/NOTICE; SYSTEM은 "SYSTEM" 고정
        ChatRole senderRole,     // NORMAL/NOTICE (그 외 null)
        String senderEmail,      // 방 주인(isRoomOwner) 수신 세션 + NORMAL/NOTICE 시만 (그 외 null)
        String displayMessage,   // NORMAL/NOTICE (그 외 null)
        String originalMessage,  // 방 주인 수신 세션만 — 강퇴 판단용 마스킹↔원문 토글 (그 외 null)
        Instant createdAt,       // NORMAL/NOTICE/ACK (그 외 null)
        UUID userId,             // KICK: 강퇴 대상 userId (그 외 null)
        Long activeCount,        // ROOM_INFO (그 외 null)
        Long retryAfterSeconds,  // RATE_LIMIT (그 외 null)
        String clientMsgId,      // ACK·ERROR·RATE_LIMIT + NORMAL/NOTICE의 발신자 수신분. 남의 메시지·SYSTEM은 null
        Boolean isRoomOwner      // ROOM_INFO: 접속자가 방 주인인지 — 프론트 방주인 토글 UI용 (#44, 그 외 null)
) {
}
