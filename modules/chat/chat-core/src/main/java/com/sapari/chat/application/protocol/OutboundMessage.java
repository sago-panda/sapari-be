package com.sapari.chat.application.protocol;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
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

    /** SYSTEM 프레임의 고정 발신자 표시명. 세 곳에 흩어져 있던 리터럴을 여기로 모은다. */
    private static final String SYSTEM_NICKNAME = "SYSTEM";

    // ── 타입별 생성자 ────────────────────────────────────────────────────────
    //
    // 이 record는 여덟 종류를 15개 nullable 필드 하나로 나른다. 그래서 직접 생성하면 호출부가
    // null을 열 개씩 늘어놓게 되고, 그 줄에서 인자 하나가 밀려도 <b>컴파일도 타입 검사도 통과한다</b>.
    // 하필 senderEmail 바로 뒤가 displayMessage라, 한 칸 밀린 실수는 발신자 이메일을 본문 자리에
    // 넣어 방 전원에게 뿌린다 — 타입 시스템이 잡아주지 못하는 PII 유출 경로다.
    //
    // 이 코드베이스가 봉투(ChatEnvelope)에서 손수 JSON을 걷어낸 이유가 정확히 같은 종류의 위험이었다.
    // 아래 팩토리는 각 종류에 실제로 의미 있는 값만 받아 그 자리를 없앤다. 직접 생성은 ArchUnit이 막는다.

    /**
     * 채팅 메시지(NORMAL·NOTICE) 렌더분.
     *
     * <p>게이팅을 여기서 한다 — 필드 정의 바로 옆이라 어떤 값이 누구에게 가는지가 한눈에 맞춰진다.
     * {@code ownerView}일 때만 이메일·원문, {@code senderView}일 때만 {@code clientMsgId}
     * (secure-by-default: 기본은 주지 않는 쪽이다).
     */
    public static OutboundMessage chat(ChatMessage message, boolean ownerView, boolean senderView) {
        return new OutboundMessage(
                typeName(message.type()), null, message.id(),
                message.senderId(), message.senderNickname(), message.senderRole(),
                ownerView ? message.senderEmail() : null,
                message.displayMessage(),
                ownerView ? message.originalMessage() : null,
                message.createdAt(),
                null, null, null,
                senderView ? message.clientMsgId() : null,
                null);
    }

    /** 전이성 신호(강퇴·종료·밴). 표시 문구는 싣지 않는다 — 클라가 {@code code}로 렌더한다. */
    public static OutboundMessage system(SystemMessageCode code) {
        return new OutboundMessage(
                "SYSTEM", code.name(), null,
                ChatConstants.SYSTEM_SENDER_ID, SYSTEM_NICKNAME, null,
                null, null, null, null, null, null, null, null, null);
    }

    /** 강퇴 알림(당사자 외 전원) — 프론트가 이 userId의 메시지를 숨긴다. */
    public static OutboundMessage kick(UUID kickedUserId) {
        return new OutboundMessage(
                "KICK", null, null, null, null, null,
                null, null, null, null, kickedUserId, null, null, null, null);
    }

    /** 입장 시 1회. {@code activeCount}가 null이면 "모른다"다 — 조회 실패에 0을 보내지 않는다. */
    public static OutboundMessage roomInfo(Long activeCount, boolean isRoomOwner) {
        return new OutboundMessage(
                "ROOM_INFO", null, null, null, null, null,
                null, null, null, null, null, activeCount, null, null, isRoomOwner);
    }

    /** 레이트리밋 거부. 서버가 준 값이든 로컬 창에서 만든 값이든 같은 모양이어야 한다. */
    public static OutboundMessage rateLimit(long retryAfterSeconds, String clientMsgId) {
        return new OutboundMessage(
                "RATE_LIMIT", null, null, null, null, null,
                null, null, null, null, null, null, retryAfterSeconds, clientMsgId, null);
    }

    /** 거부 응답. {@code clientMsgId}는 발신자가 낙관적 말풍선을 되돌릴 유일한 키다. */
    public static OutboundMessage error(String code, String clientMsgId) {
        return new OutboundMessage(
                "ERROR", code, null, null, null, null,
                null, null, null, null, null, null, null, clientMsgId, null);
    }

    /** 저장 확정. 발신자는 이 프레임으로 자기 버블을 확정한다. */
    public static OutboundMessage ack(String serverId, Instant createdAt, String clientMsgId) {
        return new OutboundMessage(
                "ACK", null, serverId, null, null, null,
                null, null, null, createdAt, null, null, null, clientMsgId, null);
    }

    /** 와이어에 실리는 타입명. SYSTEM은 영속·중계되지 않지만 sealed 전수성 때문에 남긴다. */
    private static String typeName(ChatMessageType type) {
        return switch (type) {
            case ChatMessageType.Normal normal -> "NORMAL";
            case ChatMessageType.Notice notice -> "NOTICE";
            case ChatMessageType.System system -> "SYSTEM";
        };
    }
}
