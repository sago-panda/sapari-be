package com.sapari.user.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.util.Assert;

public record WithdrawnUserRetention(
        UUID withdrawnUserRetentionId,
        UUID originalUserId,
        String nameMasked,
        String emailMasked,
        String phoneNumberMasked,
        Instant retentionUntil,
        Instant createdAt,
        Instant purgedAt
) {

    /**
     * 탈퇴회원 보존 레코드를 생성한다.
     * 원문 개인정보 대신 이미 마스킹된 값만 받아 법정 보존 만료 시각과 함께 저장한다.
     */
    public static WithdrawnUserRetention create(
            UUID originalUserId,
            String nameMasked,
            String emailMasked,
            String phoneNumberMasked,
            Instant retentionUntil
    ) {
        Assert.notNull(originalUserId, "originalUserId는 필수입니다.");
        Assert.notNull(retentionUntil, "retentionUntil은 필수입니다.");

        return new WithdrawnUserRetention(
                null,
                originalUserId,
                nameMasked,
                emailMasked,
                phoneNumberMasked,
                retentionUntil,
                null,
                null
        );
    }
}
