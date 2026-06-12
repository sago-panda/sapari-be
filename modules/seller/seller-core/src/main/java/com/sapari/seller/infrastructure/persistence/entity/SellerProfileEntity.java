package com.sapari.seller.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.storage.db.entity.BaseUuidEntity;

@Entity
@Getter
@Table(name = "seller_profile", schema = "seller_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerProfileEntity extends BaseUuidEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SellerApprovalStatus status;

    @Column(name = "store_name", nullable = false, unique = true, length = 20)
    private String storeName;

    @Column(name = "business_number", nullable = false, unique = true, length = 20)
    private String businessNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 20)
    private SellerBusinessType businessType;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "approved_at")
    private Instant approvedAt;

    public static SellerProfileEntity of(
            UUID userId,
            SellerApprovalStatus status,
            String storeName,
            String businessNumber,
            SellerBusinessType businessType,
            String rejectionReason,
            Instant approvedAt
    ) {
        SellerProfileEntity entity = new SellerProfileEntity();

        entity.userId = userId;
        entity.status = status;
        entity.storeName = storeName;
        entity.businessNumber = businessNumber;
        entity.businessType = businessType;
        entity.rejectionReason = rejectionReason;
        entity.approvedAt = approvedAt;

        return entity;
    }

    public void update(
            SellerApprovalStatus status,
            String storeName,
            String businessNumber,
            SellerBusinessType businessType,
            String rejectionReason,
            Instant approvedAt
    ) {
        this.status = status;
        this.storeName = storeName;
        this.businessNumber = businessNumber;
        this.businessType = businessType;
        this.rejectionReason = rejectionReason;
        this.approvedAt = approvedAt;
    }
}
