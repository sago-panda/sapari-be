package com.sapari.chat.command;

import java.util.UUID;

public record DeleteChatMessageCommand(
        UUID roomId,
        UUID actorId,
        String actorRole,
        String messageId
) {
    public DeleteChatMessageCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole은 필수입니다 — 인증된 주체에서 채웁니다.");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId는 필수입니다.");
        }
    }
}
