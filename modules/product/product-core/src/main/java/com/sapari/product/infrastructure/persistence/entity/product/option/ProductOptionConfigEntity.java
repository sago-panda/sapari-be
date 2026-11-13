package com.sapari.product.infrastructure.persistence.entity.product.option;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 상품별 옵션 저작 룰(조합 추가금 + 불가 조합) 통합 보관. products 핫 원장 미오염. PK = product_id (products와 1:1). 베이스 미상속 — id가 생성값이 아니라
 * product_id이기 때문. Product 애그리거트 내부 (저작 클러스터).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "product_option_configs", schema = "product_schema")
public class ProductOptionConfigEntity {

    // PK이자 ref: products.id (1:1). 앱이 주입.
    @Id
    private UUID productId;

    // 조합 추가금 룰 [{"valueIds":[...],"amount":50000}]
    @JdbcTypeCode(SqlTypes.JSON)
    private String surchargeRules;

    // 불가 조합 룰 [{"valueIds":[...],"reason":"..."}]
    @JdbcTypeCode(SqlTypes.JSON)
    private String exclusionRules;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     *
     * <p>PK가 생성값이 아닌 products와 1:1로 공유하는 product_id이므로, 앱이 productId를 직접 주입한다.
     */
    @Builder
    private ProductOptionConfigEntity(UUID productId, String surchargeRules, String exclusionRules) {
        this.productId = productId;
        this.surchargeRules = surchargeRules;
        this.exclusionRules = exclusionRules;
    }

    /**
     * 옵션 저작 룰(조합 추가금·불가 조합)을 jsonb 단위로 함께 교체한다.
     *
     * <p>두 룰은 옵션 조합을 materialize하는 한 묶음의 저작 입력이라 동시에 갱신한다.
     */
    public void updateRules(String surchargeRules, String exclusionRules) {
        this.surchargeRules = surchargeRules;
        this.exclusionRules = exclusionRules;
    }
}
