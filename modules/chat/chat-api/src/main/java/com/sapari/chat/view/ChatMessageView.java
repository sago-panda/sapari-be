package com.sapari.chat.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 채팅 메시지 응답(NORMAL·NOTICE). 직렬화되어 클라이언트로 나간다.
 *
 * <p>보안: 일반 수신자는 마스킹된 {@code displayMessage}만 받는다.
 * <b>방 주인이거나 ADMIN인 수신자에게만</b> {@code senderEmail}과 원문 {@code originalMessage}이 채워진다
 * (강퇴 판단용 마스킹↔원문 실시간 토글). 그 외에는 둘 다 {@code null}.
 * <p>⚠️ <b>실시간 팬아웃의 게이팅은 {@code toOwnerView()}가 아니다.</b> 그건 이름 그대로 방 주인만
 * 상정하고 프로덕션 호출자도 아직 없다. 실제로 쓰이는 것은 {@code ChatMessageVisibility}이고 거기서
 * 방 주인과 ADMIN이 같은 단계를 받는다. 이력 조회를 짜면서 {@code toOwnerView()}를 그대로 쓰면
 * <b>실시간은 ADMIN에게 열리고 이력은 닫히는</b> 두 갈래 정책이 된다 — 그때는 같은 판정을 쓰거나,
 * 왜 다른지를 적을 것.
 */
public record ChatMessageView(
        String id,
        UUID roomId,
        UUID senderId,
        String senderNickname,
        String senderEmail,      // 방 주인·ADMIN 수신자에게만 — 그 외 null
        String senderRole,       // NORMAL/NOTICE 발신자 역할
        String type,             // NORMAL | NOTICE
        String displayMessage,   // 욕설 마스킹된 노출 본문 (전체 수신자)
        String originalMessage,  // 원문 — 방 주인·ADMIN 수신자에게만(토글용), 그 외 null
        String clientMsgId,      // nullable — 클라 상관관계용
        Instant createdAt
) {
}
