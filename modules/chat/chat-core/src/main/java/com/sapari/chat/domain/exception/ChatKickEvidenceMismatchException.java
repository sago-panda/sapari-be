package com.sapari.chat.domain.exception;

/**
 * 강퇴 요청이 들고 온 방·대상·메시지가 서로 맞지 않는다.
 *
 * <p><b>서버 오류가 아니라 거부다.</b> 세 값을 전부 요청자가 정하므로 어긋나는 것은 정상적으로 일어날 수
 * 있는 일이고, 그래서 4xx다. {@code IllegalArgumentException}으로 두면 전역 핸들러가 잡는 그물이
 * {@code Exception} 하나뿐이라 500으로 나가고 요청마다 풀 스택이 ERROR로 쌓인다 — 인증된 판매자가
 * 잘못된 messageId로 반복 호출하는 것만으로 진짜 장애 로그가 묻힌다.
 *
 * <p>같은 이유로 도메인 안에서도 자리가 갈린다: 컴팩트 생성자의 null·필수 검증은 호출자 버그라
 * {@code IllegalArgumentException}으로 두고, 요청으로 도달할 수 있는 거부만 이 예외로 올린다.
 * live 도메인이 필수값은 {@code IllegalArgumentException}, 상태 거부는 {@code InvalidLiveStateException}
 * 으로 가르는 것과 같은 선이다.
 *
 * <p><b>어긋난 축은 로그로만 간다.</b> 응답 문구는 세 갈래가 모두 같다 — 무엇이 맞고 무엇이 틀렸는지를
 * 돌려주면 방 id나 메시지 id를 하나씩 넣어 보며 존재를 확인하는 통로가 된다.
 */
public class ChatKickEvidenceMismatchException extends ChatException {

    public ChatKickEvidenceMismatchException(String debugMessage) {
        super(ChatErrorCode.KICK_EVIDENCE_MISMATCH, debugMessage);
    }
}
