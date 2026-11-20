package com.sapari.chat.domain.exception;

/**
 * 이력 조회 입력이 계약을 어겼다 — 조회 모드(beforeId · afterId · 재생 위치) 둘 이상 지정, 범위 밖 재생 위치({@code <0} 또는 TTL 초과),
 * 범위 밖 {@code size}, 형식이 깨진 커서.
 *
 * <p><b>왜 번역이 필요한가.</b> {@code GetChatHistoryCommand} 의 불변식은 record 생성자에서
 * {@code IllegalArgumentException} 으로 던진다. 그게 맞는 모양이지만(계약은 타입에 붙어 있어야 한다)
 * 전역 핸들러에 그 타입 처리가 없어 <b>클라이언트 잘못이 500 + {@code Unhandled exception} 로그</b>로
 * 나간다 — 알림이 울리고, 고칠 쪽은 서버가 아닌데 서버 장애로 보인다.
 *
 * <p>전역 핸들러에 {@code IllegalArgumentException} 을 더하지 않은 이유는 범위다. 그 변경은 저장소
 * 전체의 응답을 바꾸고, 인프라 깊은 곳에서 올라온 같은 타입까지 400 이 된다 — 그쪽은 서버 잘못인데
 * 클라 잘못으로 보이는 반대 방향 오류다.
 *
 * <p>WebSocket 전송 경로는 이 번역이 필요 없다 — 핸들러가 {@code IllegalArgumentException} 을
 * {@code ERROR(VALIDATION)} 프레임으로 이미 바꾼다. REST 경로에만 없던 조각이다.
 */
public class ChatHistoryQueryInvalidException extends ChatException {

    public ChatHistoryQueryInvalidException(String debugMessage, Throwable cause) {
        super(ChatErrorCode.HISTORY_QUERY_INVALID, debugMessage, cause);
    }

    /**
     * 번역할 원인이 없을 때 — 서비스가 계약 위반을 <b>직접</b> 판정한 경우다(커서 형식).
     *
     * <p>위 생성자처럼 {@code IllegalArgumentException} 을 감싸는 게 아니라서 cause 가 없다. 빈
     * {@code cause} 를 넘기는 호출부를 만들지 않으려고 생성자를 따로 둔다 — {@code null} 을 인자로
     * 적는 코드는 읽는 쪽에서 "왜 null 인가" 를 매번 되묻게 한다.
     */
    public ChatHistoryQueryInvalidException(String debugMessage) {
        super(ChatErrorCode.HISTORY_QUERY_INVALID, debugMessage, null);
    }
}
