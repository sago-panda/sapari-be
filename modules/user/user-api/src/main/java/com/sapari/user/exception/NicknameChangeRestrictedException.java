package com.sapari.user.exception;

/** 잠금 후 닉네임 변경 간격 위반을 호출자 도메인의 오류로 변환하기 위한 port 예외다. */
public class NicknameChangeRestrictedException extends RuntimeException {

    /** 사용자 입력이나 개인정보를 예외 메시지에 포함하지 않는다. */
    public NicknameChangeRestrictedException() {
        super("Nickname change interval has not elapsed");
    }
}
