package com.sapari.user.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.sapari.storage.db.entity.UuidTimeEntity;

/**
 * 가입 약관 증적 영속성 엔티티다.
 */
@Entity
@Getter
@Table(name = "user_terms_agreements", schema = "user_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTermsAgreementEntity extends UuidTimeEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID termsId;

    @Column(nullable = false)
    private boolean agreed;

    @Column(nullable = false)
    private Instant agreedAt;

    public static UserTermsAgreementEntity of(
            UUID userId,
            UUID termsId,
            boolean agreed,
            Instant agreedAt
    ) {
        UserTermsAgreementEntity entity = new UserTermsAgreementEntity();
        entity.userId = userId;
        entity.termsId = termsId;
        entity.agreed = agreed;
        entity.agreedAt = agreedAt;
        return entity;
    }
}
