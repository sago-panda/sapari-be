package com.sapari.chat.domain.model;

import java.util.UUID;

/**
 * 강퇴 사유가 된 메시지에서 <b>증거 판단에 필요한 것만</b> 떼어낸 조각.
 *
 * <p>{@code ChatMessage}를 그대로 쓰지 않는 이유가 둘이다. 하나는 이 경로가 발신자 이메일을 알 필요가
 * 없다는 것 — 필요 없는 PII를 읽어 오지 않으면 그 값이 새어나갈 자리도 생기지 않는다. 다른 하나는 이
 * 조각이 무엇에 쓰이는지가 타입에 드러난다는 것이다. 강퇴는 "이 방에서 이 사람이 한 말"이 맞는지를
 * 확인하고 그 말을 박제하는 일이고, 여기 담긴 세 필드가 정확히 그 일에 필요한 전부다.
 *
 * <p>{@code originalMessage}는 <b>마스킹 전</b> 원문이다. 무엇 때문에 끊었는지가 증거의 전부라
 * {@code ***}로 가려진 본문은 증거가 되지 못한다.
 */
public record ChatMessageEvidence(
        UUID roomId,
        UUID senderId,
        String originalMessage
) {
    public ChatMessageEvidence {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId는 필수입니다.");
        }
        if (senderId == null) {
            throw new IllegalArgumentException("senderId는 필수입니다.");
        }
        if (originalMessage == null || originalMessage.isBlank()) {
            throw new IllegalArgumentException("originalMessage는 필수입니다.");
        }
    }
}
