package com.sapari.chat.command;

import java.util.UUID;

/**
 * 방의 현재 공지 조회 입력 (live-app REST).
 *
 * <p>신원 규칙은 {@link GetChatHistoryCommand} 와 같다 — 비회원도 조회하므로 요청자가 없을 수 있고,
 * 있으면 id 와 역할이 한 쌍이다. 같은 판정(특권 뷰)을 같은 입력으로 하기 때문이다.
 */
public record GetChatNoticeCommand(
        UUID roomId,
        UUID requesterId,          // nullable — 비회원 조회. 있으면 requesterRole 도 있어야 한다
        String requesterRole       // nullable — ChatRole 이름. requesterId 와 쌍이다
) {
    public GetChatNoticeCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (requesterId != null && (requesterRole == null || requesterRole.isBlank())) {
            throw new IllegalArgumentException("requesterId가 있으면 requesterRole도 필요합니다.");
        }
        if (requesterId == null && requesterRole != null && !requesterRole.isBlank()) {
            throw new IllegalArgumentException("requesterRole이 있으면 requesterId도 필요합니다.");
        }
    }

    /** 특권 뷰 판정에 쓸 신원이 있는가 — 비회원이면 없다. */
    public boolean hasRequesterIdentity() {
        return requesterId != null;
    }
}
