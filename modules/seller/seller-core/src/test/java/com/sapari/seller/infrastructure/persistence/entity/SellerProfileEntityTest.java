package com.sapari.seller.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.storage.db.entity.UuidTimeEntity;

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
        assertThat(entity).isInstanceOf(UuidTimeEntity.class);
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

    @Test
    @DisplayName("마이그레이션 테이블명과 동일한 seller_profiles를 사용한다")
    void tableNameMatchesMigration() {
        // when
        Table table = SellerProfileEntity.class.getAnnotation(Table.class);

        // then
        assertThat(table.name()).isEqualTo("seller_profiles");
        assertThat(table.schema()).isEqualTo("seller_schema");
    }

    @Test
    @DisplayName("판매자 프로필 주요 컬럼 길이는 마이그레이션과 동일한 20자를 사용한다")
    void mainColumnLengthsMatchMigration() throws NoSuchFieldException {
        assertThat(columnLength("status")).isEqualTo(20);
        assertThat(columnLength("storeName")).isEqualTo(20);
        assertThat(columnLength("businessNumber")).isEqualTo(20);
        assertThat(columnLength("businessType")).isEqualTo(10);
    }

    private int columnLength(String fieldName) throws NoSuchFieldException {
        return SellerProfileEntity.class.getDeclaredField(fieldName)
                .getAnnotation(Column.class)
                .length();
    }
}
