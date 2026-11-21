package com.sapari.seller.command;

public record SellerLoginCommand(
        String email,
        String password
) {
    /** 로그·디버그 출력에 비밀번호와 개인정보가 포함되지 않도록 고정 문자열만 반환한다. */
    @Override
    public String toString() {
        return "SellerLoginCommand[REDACTED]";
    }

}
