package com.sapari.chat.domain.exception;

/**
 * 같은 사용자에 대한 강퇴가 겹쳐 이 요청이 제한 시간 안에 자기 차례를 얻지 못했다.
 *
 * <p><b>고장이 아니라 혼잡이다.</b> 밴은 사용자당 한 행이라 서로 다른 방의 두 운영자가 같은 사람을
 * 동시에 강퇴하면 한쪽이 상대의 커밋을 기다린다. 정상 대기는 밀리초라 여기까지 오지 않지만, 상한을
 * 넘으면 기다리는 대신 실패한다 — 커넥션을 쥔 줄이 풀보다 길어지면 강퇴와 무관한 요청까지 굶기 때문이다.
 *
 * <p>이 예외를 따로 두는 이유는 <b>상태코드와 로그 등급</b>이다. 번역하지 않으면 인프라 예외가 전역
 * 핸들러의 마지막 그물에 걸려 500 + {@code log.error("Unhandled exception")}이 된다. 그러면 두 운영자가
 * 동시에 강퇴하는 정상 상황이 서버 고장으로 보이고 알림까지 울린다. 재시도하면 성공하는 실패에는
 * 재시도하라고 말해 주는 응답이 맞다.
 */
public class ChatKickContendedException extends ChatException {

    public ChatKickContendedException(String message, Throwable cause) {
        super(ChatErrorCode.KICK_CONTENDED, message, cause);
    }
}
