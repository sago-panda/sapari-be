package com.sapari.chat.command;

import java.util.UUID;

/**
 * 채팅 이력 조회 입력 (api-app REST).
 *
 * <p>requesterId는 senderEmail 노출 게이팅(요청자 == 방 주인일 때만 이메일 포함)에 쓰인다.
 * beforeId(커서)와 playbackPositionMs(VOD 위치)는 상호 배타 — 둘 다 지정 시 예외.
 */
public record GetChatHistoryCommand(
        UUID roomId,
        UUID requesterId,          // 이메일 게이팅용 — 컨트롤러가 인증 principal에서 채움
        String beforeId,           // 커서(nullable) — playbackPositionMs와 배타
        Long playbackPositionMs,   // VOD 위치(nullable) — beforeId와 배타
        int size                   // 페이지 크기
) {
    public GetChatHistoryCommand {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("requesterId는 필수입니다.");
        }
        if (beforeId != null && playbackPositionMs != null) {
            throw new IllegalArgumentException("beforeId와 playbackPositionMs는 동시에 지정할 수 없습니다.");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size는 1~100 범위여야 합니다.");
        }
    }
}
