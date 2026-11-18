package com.sapari.chat.view;

import java.util.List;

/**
 * 채팅 이력 조회 결과 — 과거 스크롤 · 재접속 누락분 · VOD 다시보기가 같이 쓴다.
 *
 * <p>{@code messages}는 백엔드에서 이미 강퇴자 메시지를 제외한 목록이다.
 * (라이브 중 강퇴자 숨김은 WS KICK 이벤트로 실시간 처리하므로, 별도 강퇴자 ID 집합은 반환하지 않는다.)
 * ⚠️ 재접속({@code afterId})에서는 끊긴 동안 지나간 KICK 을 이 경로가 덮지 못한다 — 끊기기 전에 이미
 * 그려 둔 강퇴자 메시지가 화면에 남는다. 집합을 안 주는 결정은 그 차이를 알고 유지한 것이다(프론트 가이드 §7.4).
 */
public record GetChatHistoryView(
        List<ChatMessageView> messages
) {
}
