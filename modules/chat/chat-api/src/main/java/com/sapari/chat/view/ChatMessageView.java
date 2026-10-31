package com.sapari.chat.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 채팅 메시지 응답(NORMAL·NOTICE). 직렬화되어 클라이언트로 나간다.
 *
 * <p>보안: 일반 수신자는 마스킹된 {@code displayMessage}만 받는다.
 * <b>방 주인이거나 ADMIN인 수신자에게만</b> {@code senderEmail}과 원문 {@code originalMessage}이 채워진다
 * (강퇴 판단용 마스킹↔원문 실시간 토글). 그 외에는 둘 다 {@code null}.
 * <p><b>게이팅은 한 자리에서 한다</b>({@code ChatPermissionPolicy.seesUnmaskedContent} — 방 주인
 * 또는 ADMIN). 실시간 팬아웃과 이력 조회가 <b>같은 판정</b>을 쓰므로 같은 질문에 두 답이 없다.
 * 한때 이 자리에 "이력을 짤 때 {@code toOwnerView()}를 그대로 쓰면 실시간은 ADMIN에게 열리고 이력은
 * 닫힌다" 는 경고가 있었다 — 이력이 붙으면서 같은 판정으로 통일했고, 이름도 {@code toPrivilegedView}
 * 로 넓혀 "소유만" 으로 읽히던 입구를 없앴다.
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
