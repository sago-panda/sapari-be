package com.sapari.chat.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 채팅 메시지 응답(NORMAL·NOTICE). 직렬화되어 클라이언트로 나간다.
 *
 * <p>보안: 일반 수신자는 마스킹된 {@code displayMessage}만 받는다.
 * <b>방 주인 수신자에게만</b> {@code senderEmail}과 원문 {@code originalMessage}이 채워진다
 * (강퇴 판단용 마스킹↔원문 실시간 토글). 그 외에는 둘 다 {@code null}.
 * 게이팅은 {@code ChatMessage.toView()}=제외 / {@code toOwnerView()}=포함으로 강제한다.
 */
public record ChatMessageView(
        String id,
        UUID roomId,
        UUID senderId,
        String senderNickname,
        String senderEmail,      // 방 주인 수신자에게만 — 그 외 null
        String senderRole,       // NORMAL/NOTICE 발신자 역할
        String type,             // NORMAL | NOTICE
        String displayMessage,   // 욕설 마스킹된 노출 본문 (전체 수신자)
        String originalMessage,  // 원문 — 방 주인 수신자에게만(토글용), 그 외 null
        String clientMsgId,      // nullable — 클라 상관관계용
        Instant createdAt
) {
}
