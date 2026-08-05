package com.sapari.user.command;

import java.util.UUID;

import com.sapari.user.model.ProviderType;

/**
 * 방금 생성한 소셜 고객 가입 데이터만 안전하게 보상 삭제하기 위한 식별자 묶음이다.
 */
public record SocialCustomerRegistrationRollbackCommand(
        UUID userId,
        ProviderType provider,
        String providerId,
        String email
) {
}
