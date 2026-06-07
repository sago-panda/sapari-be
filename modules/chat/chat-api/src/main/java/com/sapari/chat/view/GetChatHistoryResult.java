package com.sapari.chat.view;

import java.util.List;

/**
 * 채팅 이력 페이징 결과 (VOD 다시보기).
 *
 * <p>{@code messages}는 백엔드에서 이미 강퇴자 메시지를 제외한 목록이다.
 * (라이브 중 강퇴자 숨김은 WS KICK 이벤트로 실시간 처리하므로, 별도 강퇴자 ID 집합은 반환하지 않는다.)
 */
public record GetChatHistoryResult(
        List<ChatMessageView> messages
) {
}
