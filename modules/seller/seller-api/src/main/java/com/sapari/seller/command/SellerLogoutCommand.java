package com.sapari.seller.command;

public record SellerLogoutCommand(
        String refreshToken
) {
    /** refreshToken 이 로그·디버그 출력에 남지 않게 가린다. */
    @Override
    public String toString() {
        return "SellerLogoutCommand[refreshToken=REDACTED]";
    }
}
