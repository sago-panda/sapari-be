package com.sapari.seller.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.infrastructure.persistence.entity.SellerProfileEntity;

@DisplayName("판매자 프로필 매퍼 테스트")
class SellerProfileMapperTest {

    private final SellerProfileMapper mapper = Mappers.getMapper(SellerProfileMapper.class);

    @Test
    @DisplayName("도메인 모델을 JPA 엔티티로 변환한다")
    void toEntityMapsDomainToEntity() {
        // given
        UUID userId = UUID.randomUUID();
        SellerProfile sellerProfile = new SellerProfile(
                null,
                userId,
                SellerApprovalStatus.PENDING,
                "사파리 상점",
                "1234567890",
                SellerBusinessType.INDIVIDUAL,
                null,
                null
        );

        // when
        SellerProfileEntity entity = mapper.toEntity(sellerProfile);

        // then
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getStatus()).isEqualTo(SellerApprovalStatus.PENDING);
        assertThat(entity.getStoreName()).isEqualTo("사파리 상점");
        assertThat(entity.getBusinessNumber()).isEqualTo("1234567890");
        assertThat(entity.getBusinessType()).isEqualTo(SellerBusinessType.INDIVIDUAL);
        assertThat(entity.getRejectionReason()).isNull();
        assertThat(entity.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("JPA 엔티티를 도메인 모델로 변환한다")
    void toDomainMapsEntityToDomain() {
        // given
        UUID userId = UUID.randomUUID();
        Instant approvedAt = Instant.parse("2026-01-01T10:00:00Z");
        SellerProfileEntity entity = SellerProfileEntity.of(
                userId,
                SellerApprovalStatus.APPROVED,
                "사파리 상점",
                "1234567890",
                SellerBusinessType.CORPORATE,
                null,
                approvedAt
        );

        // when
        SellerProfile sellerProfile = mapper.toDomain(entity);

        // then
        assertThat(sellerProfile.userId()).isEqualTo(userId);
        assertThat(sellerProfile.status()).isEqualTo(SellerApprovalStatus.APPROVED);
        assertThat(sellerProfile.storeName()).isEqualTo("사파리 상점");
        assertThat(sellerProfile.businessNumber()).isEqualTo("1234567890");
        assertThat(sellerProfile.businessType()).isEqualTo(SellerBusinessType.CORPORATE);
        assertThat(sellerProfile.rejectionReason()).isNull();
        assertThat(sellerProfile.approvedAt()).isEqualTo(approvedAt);
    }

    @Test
    @DisplayName("도메인 모델 값으로 기존 엔티티를 수정한다")
    void updateEntityFromDomainUpdatesEntity() {
        // given
        UUID userId = UUID.randomUUID();
        SellerProfileEntity entity = SellerProfileEntity.of(
                userId,
                SellerApprovalStatus.PENDING,
                "사파리 상점",
                "1234567890",
                SellerBusinessType.INDIVIDUAL,
                null,
                null
        );
        Instant approvedAt = Instant.parse("2026-01-01T10:00:00Z");
        SellerProfile sellerProfile = new SellerProfile(
                UUID.randomUUID(),
                userId,
                SellerApprovalStatus.APPROVED,
                "사파리 기업 상점",
                "0987654321",
                SellerBusinessType.CORPORATE,
                null,
                approvedAt
        );

        // when
        mapper.updateEntityFromDomain(entity, sellerProfile);

        // then
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getStatus()).isEqualTo(SellerApprovalStatus.APPROVED);
        assertThat(entity.getStoreName()).isEqualTo("사파리 기업 상점");
        assertThat(entity.getBusinessNumber()).isEqualTo("0987654321");
        assertThat(entity.getBusinessType()).isEqualTo(SellerBusinessType.CORPORATE);
        assertThat(entity.getApprovedAt()).isEqualTo(approvedAt);
    }
}
