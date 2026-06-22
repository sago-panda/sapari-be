package com.sapari.user.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.util.Assert;

/**
 * 회원가입 시점에 사용자가 특정 약관 버전에 동의했는지 남기는 증적 모델이다.
 * termsId는 FK 없는 soft reference지만 Terms row를 불변 기준 데이터로 운영한다는 전제에서 당시 약관 버전을 가리킨다.
 * agreed=false는 선택 약관(MARKETING)에 대한 명시적 거부 이력으로 사용한다.
 */
public record UserTermsAgreement(
        UUID userTermsAgreementId,
        UUID userId,
        UUID termsId,
        boolean agreed,
        Instant agreedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserTermsAgreement create(
            UUID userId,
            UUID termsId,
            boolean agreed,
            Instant agreedAt
    ) {
        return of(null, userId, termsId, agreed, agreedAt, null, null);
    }

    public static UserTermsAgreement of(
            UUID userTermsAgreementId,
            UUID userId,
            UUID termsId,
            boolean agreed,
            Instant agreedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        Assert.notNull(userId, "userId는 필수입니다.");
        Assert.notNull(termsId, "termsId는 필수입니다.");
        Assert.notNull(agreedAt, "agreedAt은 필수입니다.");

        return new UserTermsAgreement(
                userTermsAgreementId,
                userId,
                termsId,
                agreed,
                agreedAt,
                createdAt,
                updatedAt
        );
    }
}
