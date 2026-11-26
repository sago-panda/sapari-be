package com.sapari.chat.domain.model;

import java.util.UUID;

/**
 * 삭제 권한 판정에 필요한 것만 떼어 낸 메시지 조각 — 누가 썼고 그때 어떤 역할이었나.
 *
 * <p>{@code deleted} 는 이미 삭제 표시가 있는지다. 다시 삭제하는 요청을 성공으로 끝내되 감사 행은
 * 한 번만 남기는 데 쓴다(발행은 매번 한다 — {@code DeleteChatMessageService}).
 */
public record ChatMessageDeletionTarget(
        UUID senderId,
        ChatRole senderRole,
        boolean deleted
) {
    public ChatMessageDeletionTarget {
        if (senderId == null) {
            throw new IllegalArgumentException("senderId는 필수입니다.");
        }
        if (senderRole == null) {
            throw new IllegalArgumentException("senderRole은 필수입니다.");
        }
    }
}
