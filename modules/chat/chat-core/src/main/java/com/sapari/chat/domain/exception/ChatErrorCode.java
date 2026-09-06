package com.sapari.chat.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.sapari.common.core.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

    PERMISSION_DENIED(403, "CHAT-001", "채팅 권한이 없습니다."),
    RATE_LIMIT_EXCEEDED(429, "CHAT-002", "메시지를 너무 빠르게 보내고 있습니다."),
    USER_KICKED(403, "CHAT-003", "강퇴되어 채팅에 참여할 수 없습니다."),
    LIVE_NOT_ACTIVE(400, "CHAT-004", "진행 중인 라이브가 아닙니다."),
    // 사용자 잘못이 아니라 서버 쪽 상태의 문제라 5xx다. 어느 키가 문제인지는 응답이 아니라 로그로 간다
    // (BusinessException의 debugMessage가 로그용, 응답은 이 문구 — 내부 식별자 누출 방지).
    KICK_STORE_CORRUPTED(500, "CHAT-005", "강퇴 처리를 완료할 수 없습니다."),
    // 요청이 들고 온 방·대상·메시지가 서로 맞지 않는다. 요청자가 값을 정하므로 4xx다.
    // 문구가 세 갈래(메시지 없음·다른 방·다른 작성자)에 공통인 것은 의도다 — 어디까지 맞았는지를
    // 알려주면 방·메시지 id를 더듬는 통로가 된다. 어느 축이 어긋났는지는 debugMessage로 로그에만 간다.
    KICK_EVIDENCE_MISMATCH(400, "CHAT-006", "강퇴 증거를 확인할 수 없습니다."),

    // 강퇴(CHAT-003)와 갈라 둔다 — 강퇴는 그 방 하나이고 밴은 계정 전체라, 클라이언트가
    // "다른 방으로 가면 된다"를 안내할 수 있는지가 갈린다.
    USER_BANNED(403, "CHAT-007", "이용이 제한되어 채팅에 참여할 수 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
