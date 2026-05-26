package com.sapari.member.command;

public record MemberOAuthCommand(
        String provider,
        String providerId,
        String providerEmail,
        String name,
        String profileImageUrl
) {
}
