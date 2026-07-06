package com.sapari.streamingapp.websocket;

/**
 * 클라이언트 → 서버 WS 메시지(핸들러가 파싱하는 신뢰경계 입력). 서버 신뢰값(roomId·senderId·role·owner 등)은
 * 절대 여기 두지 않는다 — 그건 룸 토큰에서 온 ChatSession에서만 가져온다.
 *
 * @param type        NORMAL | NOTICE
 * @param content     원문(서버가 욕설 필터링)
 * @param clientMsgId 클라 생성 UUID — 낙관적 렌더 reconcile·재전송 멱등 키(nullable이지만 권장 필수)
 */
public record InboundMessage(
        String type,
        String content,
        String clientMsgId
) {
}
