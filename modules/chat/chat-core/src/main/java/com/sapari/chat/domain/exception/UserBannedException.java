package com.sapari.chat.domain.exception;

/**
 * 밴된 사용자가 발화를 시도했다.
 *
 * <p>강퇴({@link UserKickedException})와 갈라 두는 이유는 <b>범위가 다르기 때문</b>이다. 강퇴는 그 방
 * 하나이고, 밴은 계정 전체다. 클라이언트가 둘을 같은 코드로 받으면 "이 방만 안 되는 것"과 "어느 방도
 * 안 되는 것"을 구분하지 못해, 다른 방으로 옮기라고 안내하는 화면이 밴에도 그대로 뜬다.
 *
 * <p>이 예외가 나온다는 것은 <b>밴이 걸릴 때 이 세션이 다른 방에 열려 있었다</b>는 뜻이다. 입장 게이트는
 * 새 접속만 막으므로, 이미 열린 세션은 여기서 처음 걸린다.
 */
public class UserBannedException extends ChatException {

    public UserBannedException(String message) {
        super(ChatErrorCode.USER_BANNED, message);
    }
}
