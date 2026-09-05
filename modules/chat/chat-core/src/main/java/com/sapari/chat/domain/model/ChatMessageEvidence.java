package com.sapari.chat.domain.model;

import java.util.UUID;

/**
 * 강퇴 사유가 된 메시지에서 <b>증거 판단에 필요한 것만</b> 떼어낸 조각.
 *
 * <p>{@code ChatMessage}를 그대로 쓰지 않는 이유가 둘이다. 하나는 이 경로가 발신자 이메일을 알 필요가
 * 없다는 것 — 필요 없는 PII를 <b>이 타입 밖으로 내보내지 않으면</b> 그 값이 흘러갈 자리가 줄어든다
 * (문서를 읽어 오는 단계에서까지 걷어내는 것은 어댑터 몫이고, 지금은 하지 않는다). 다른 하나는 이
 * 조각이 무엇에 쓰이는지가 타입에 드러난다는 것이다. 강퇴는 "이 방에서 이 사람이 한 말"이 맞는지를
 * 확인하고 그 말을 박제하는 일이고, 여기 담긴 네 필드가 정확히 그 일에 필요한 전부다.
 *
 * <p>{@code originalMessage}는 <b>마스킹 전</b> 원문이다. 무엇 때문에 끊었는지가 증거의 전부라
 * {@code ***}로 가려진 본문은 증거가 되지 못한다.
 *
 * <p><b>{@code senderRole}은 그 메시지를 쓸 때의 역할이다</b>(지금 조회한 값이 아니다). 강퇴 권한 판정은
 * 관리자를 끊지 못하게 하는 데에만 이 값을 쓰는데, 그 역할은 발신 시점에 live가 서명한 룸 토큰에서 왔으므로
 * 위조되지 않는다. 지금 값을 다시 묻지 않는 것이 요점이다 — 그러려면 이 경로가 사용자 계정 저장소에 닿아야
 * 하고, 강퇴 하나 때문에 호스트 앱이 계정 도메인 전체를 갖게 된다. 대신 발신 이후 승격된 관리자는 이
 * 증거로 강퇴될 수 있다. 판단 대상이 그 메시지라 어긋난 값이 아니다.
 */
public record ChatMessageEvidence(
        UUID roomId,
        UUID senderId,
        ChatRole senderRole,
        String originalMessage
) {
    public ChatMessageEvidence {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (senderId == null) {
            throw new IllegalArgumentException("senderId는 필수입니다.");
        }
        if (senderRole == null) {
            throw new IllegalArgumentException("senderRole은 필수입니다.");
        }
        if (originalMessage == null || originalMessage.isBlank()) {
            throw new IllegalArgumentException("originalMessage는 필수입니다.");
        }
    }
}
