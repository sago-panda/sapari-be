package com.sapari.seller.command;

public record SellerNicknameUpdateCommand(
        String nickname,
        String accessToken
) {
    /** accessToken 이 로그·디버그 출력에 남지 않게 가린다. */
    @Override
    public String toString() {
        return "SellerNicknameUpdateCommand[nickname=" + nickname + ", accessToken=REDACTED]";
    }
}
