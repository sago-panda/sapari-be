package com.sapari.seller.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.seller.domain.model.SellerApprovalStatus;
import com.sapari.seller.domain.model.SellerBusinessType;

@DisplayName("판매자 프로필 엔티티 테스트")
class SellerProfileEntityTest {

    @Test
    @DisplayName("판매자 프로필 엔티티를 생성한다")
    void ofReturnsSellerProfileEntity() {
        // given
        UUID userId = UUID.randomUUID();
        Instant approvedAt = Instant.parse("2026-01-01T10:00:00Z");

        // when
        SellerProfileEntity entity = SellerProfileEntity.of(
                userId,
                SellerApprovalStatus.APPROVED,
                "사파리 상점",
                "1234567890",
                SellerBusinessType.CORPORATE,
                null,
                approvedAt
        );

        // then
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getStatus()).isEqualTo(SellerApprovalStatus.APPROVED);
        assertThat(entity.getStoreName()).isEqualTo("사파리 상점");
        assertThat(entity.getBusinessNumber()).isEqualTo("1234567890");
        assertThat(entity.getBusinessType()).isEqualTo(SellerBusinessType.CORPORATE);
        assertThat(entity.getRejectionReason()).isNull();
        assertThat(entity.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    @DisplayName("판매자 프로필 엔티티를 수정한다")
    void updateChangesSellerProfileEntity() {
        // given
        SellerProfileEntity entity = SellerProfileEntity.of(
                UUID.randomUUID(),
                SellerApprovalStatus.PENDING,
                "사파리 상점",
                "1234567890",
                SellerBusinessType.INDIVIDUAL,
                null,
                null
        );
        Instant approvedAt = Instant.parse("2026-01-01T10:00:00Z");

        // when
        entity.update(
                SellerApprovalStatus.APPROVED,
                "사파리 기업 상점",
                "0987654321",
                SellerBusinessType.CORPORATE,
                null,
                approvedAt
        );

        // then
        assertThat(entity.getStatus()).isEqualTo(SellerApprovalStatus.APPROVED);
        assertThat(entity.getStoreName()).isEqualTo("사파리 기업 상점");
        assertThat(entity.getBusinessNumber()).isEqualTo("0987654321");
        assertThat(entity.getBusinessType()).isEqualTo(SellerBusinessType.CORPORATE);
        assertThat(entity.getRejectionReason()).isNull();
        assertThat(entity.getApprovedAt()).isEqualTo(approvedAt);
    }
}
