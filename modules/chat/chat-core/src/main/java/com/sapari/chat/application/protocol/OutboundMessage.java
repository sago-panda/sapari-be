package com.sapari.chat.application.protocol;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.sapari.chat.domain.model.ChatRole;

/**
 * 서버 → 클라이언트 렌더 메시지. chat-core 소유(senderRole이 ChatRole(도메인)이라 chat-api 불가).
 *
 * <p>필드 유무는 {@code type}에 따라 달라진다(아래 주석). ChatSessionManager.sendToSession 파라미터이며,
 * 생산은 SendChatService 에코 폴백·SystemMessageService 렌더·ChatBroadcastSubscriber가 맡는다.
 *
 * <p><b>값이 없는 필드는 와이어에 싣지 않는다.</b> 이 record는 여덟 종류의 메시지를 한 모양으로 나르는
 * 합집합이라 어떤 종류든 절반 넘게 비어 있다 — NORMAL 수신분은 15개 중 7개만 값이 있다. 비운 채로
 * 내보내면 나머지가 전부 {@code "이름":null,}로 나가고, 그 패딩이 메시지 하나에서 실어 나르는 본문보다
 * 커진다. 방 하나가 초당 수백 건을 뿌리는 구조라 그 차이가 그대로 대역폭이 된다.
 *
 * <p><b>프론트 계약</b>: 없는 값은 {@code null}이 아니라 <b>키 자체가 없다</b>. 두 경우를 같게 다루는
 * 검사(값의 참·거짓, {@code == null})는 그대로 동작하지만 {@code === null} 비교는 깨진다.
 * "모른다"를 뜻하던 값(조회 실패 시의 {@code activeCount})도 이제 키 부재로 온다 — 뜻은 같다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
