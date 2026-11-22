package com.sapari.customer.command;

public record CustomerNicknameUpdateCommand(
        String nickname,
        String accessToken
) {
    /** accessToken 이 로그·디버그 출력에 남지 않게 가린다. */
    @Override
    public String toString() {
        return "CustomerNicknameUpdateCommand[nickname=" + nickname + ", accessToken=REDACTED]";
    }
}
