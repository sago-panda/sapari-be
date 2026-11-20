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
        String senderEmail,        // 방 주인·ADMIN에게만 노출 — SYSTEM은 null (이메일은 불변이라 snapshot 아님)
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
     * 특권 수신자 view — senderEmail과 원문을 포함(마스킹↔원문 토글).
     *
     * <p><b>누가 특권인지 여기서 정하지 않는다.</b> 판정은 {@code ChatPermissionPolicy.seesUnmaskedContent}
     * 한 자리에서만 하고(방 주인 또는 관리자), 이 메서드는 그 판정이 참일 때 무엇을 담을지만 안다.
     *
     * <p>한때 이름이 {@code toOwnerView} 였고 javadoc 이 "이력 조회를 짜면서 이걸 그대로 쓰면 실시간은
     * 관리자에게 열리고 이력은 닫히는 두 갈래 정책이 된다" 고 경고했다. 이력 조회가 붙으면서 그 질문에
     * 답했다 — <b>같은 판정을 쓴다</b>. 이름이 좁아서 다음 사람이 "소유만" 으로 읽는 것이 그 함정의
     * 입구였으므로 이름도 함께 넓혔다.
     */
    public ChatMessageView toPrivilegedView() {
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
