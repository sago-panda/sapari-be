package com.sapari.product.infrastructure.persistence.entity.discount;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 할인 정책 ↔ 상품 매핑 (product-level).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "discount_policy_products", schema = "product_schema")
public class DiscountPolicyProductEntity extends BaseUuidEntity {

    // ref: discount_policies.id. 물리 FK 미사용.
    private UUID discountPolicyId;

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     */
    @Builder
    private DiscountPolicyProductEntity(UUID discountPolicyId, UUID productId) {
        this.discountPolicyId = discountPolicyId;
        this.productId = productId;
    }
}
