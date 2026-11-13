package com.sapari.chat.domain.exception;

/**
 * 같은 {@code clientMsgId} 로 이미 저장된 메시지와 다른 본문이 왔다({@link ChatErrorCode#CLIENT_MSG_ID_REUSED}).
 *
 * <p>같은 본문이면 재전송이고 먼저 저장된 메시지를 돌려준다. 본문이 다르면 재전송이 아니라 키 재사용이라,
 * 먼저 저장된 메시지를 돌려주면 이번 본문이 저장도 발행도 안 된 채 성공으로 보고된다.
 */
public class ClientMsgIdReusedException extends ChatException {

    public ClientMsgIdReusedException() {
        super(ChatErrorCode.CLIENT_MSG_ID_REUSED);
    }
}
