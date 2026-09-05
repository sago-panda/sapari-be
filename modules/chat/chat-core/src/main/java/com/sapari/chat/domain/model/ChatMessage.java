package com.sapari.chat.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import com.sapari.chat.view.ChatMessageView;

@Builder(toBuilder = true)
public record ChatMessage(
        String id,                 // MongoDB ObjectId의 String 추상화 — 영속 전에는 null
        UUID roomId,
        UUID senderId,             // SYSTEM 메시지는 고정 시스템 UUID
        String senderNickname,     // 발신 시점 snapshot — 이후 닉네임 변경에 영향받지 않는다
        String senderEmail,        // 판매자에게만 노출되는 발신자 이메일 — SYSTEM은 null (이메일은 불변이라 snapshot 아님)
        ChatRole senderRole,
        ChatMessageType type,
        String originalMessage,
        String displayMessage,     // 욕설 필터링이 적용된 노출용 본문
        String clientMsgId,        // 클라이언트 생성 UUID — 재전송 중복 방지(nullable)
        Instant createdAt
) {
    public ChatMessage {
        // 메시지 종류와 무관하게 항상 성립해야 하는 불변식만 검증한다.
        // 본문·닉네임·이메일은 종류(NORMAL/NOTICE/SYSTEM)에 따라 유무가 달라 여기서 강제하지 않는다.
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (senderId == null) {
            throw new IllegalArgumentException("senderId는 필수입니다.");
        }
        if (senderRole == null) {
            throw new IllegalArgumentException("senderRole은 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type은 필수입니다.");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt은 필수입니다.");
        }
    }

    /** 마스킹 본문만 담은 view. senderEmail·원문은 제외 — 누출 방지 기본값. */
    public ChatMessageView toView() {
        return view(null, null);
    }

    /**
     * 방 주인 수신자 전용 view — senderEmail과 원문을 포함(마스킹↔원문 토글).
     * 수신자의 방 소유를 확인한 경우에만 호출한다.
     *
     * <p>⚠️ <b>이름이 좁다 — 실시간 게이팅과 다르다.</b> 팬아웃은 방 주인 <b>또는 관리자</b>에게
     * 원문·이메일을 주는데({@code ChatMessageVisibility.FULL}) 이 메서드는 이름 그대로 방 주인만 상정한다.
     * 아직 프로덕션 호출자가 없지만, 이력 조회를 짜면서 이걸 그대로 쓰면 <b>실시간은 관리자에게 열리고
     * 이력은 닫히는</b> 두 갈래 정책이 된다. 그때는 팬아웃과 같은 판정을 쓰거나, 왜 다른지를 적을 것.
     */
    public ChatMessageView toOwnerView() {
        return view(senderEmail, originalMessage);
    }

    private ChatMessageView view(String emailOrNull, String originalOrNull) {
        return new ChatMessageView(
                id, roomId, senderId, senderNickname, emailOrNull,
                senderRole.name(), typeName(), displayMessage, originalOrNull, clientMsgId, createdAt
        );
    }

    private String typeName() {
        return switch (type) {
            case ChatMessageType.Normal n -> "NORMAL";
            case ChatMessageType.Notice n -> "NOTICE";
            case ChatMessageType.System s -> "SYSTEM";
        };
    }
}
