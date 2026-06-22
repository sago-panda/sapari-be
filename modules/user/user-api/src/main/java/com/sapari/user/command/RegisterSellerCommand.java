package com.sapari.user.command;

public record RegisterSellerCommand(
        String nickname,
        String name,
        String phoneNumber,
        String email,
        boolean privacyAgreed,
        boolean marketingAgreed
) {
}
