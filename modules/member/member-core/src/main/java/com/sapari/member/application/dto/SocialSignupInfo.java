package com.sapari.member.application.dto;

import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.user.domain.model.ProviderType;

public record SocialSignupInfo(
        ProviderType provider,
        String providerId,
        String providerEmail,
        String name,
        String profileImageUrl
) {

    public static SocialSignupInfo from(MemberOAuthCommand command) {
        return new SocialSignupInfo(
                ProviderType.valueOf(command.provider()),
                command.providerId(),
                command.providerEmail(),
                command.name(),
                command.profileImageUrl()
        );
    }
}
