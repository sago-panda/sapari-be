package com.sapari.product.infrastructure.persistence.entity.productgroup;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 그룹 구성 아이템의 영속 엔티티 (table {@code product_group_items}). {@code groupSetId}·{@code productId}를 물리 FK 없이 참조하며
 * {@code sortOrder}로 노출 순서를 가진다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_group_items", schema = "product_schema")
public class ProductGroupItemEntity extends BaseUuidEntity {

    // ref: product_group_sets.id. 물리 FK 미사용.
    private UUID groupSetId;

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    private Integer sortOrder;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 한다.
     */
    @Builder
    public ProductGroupItemEntity(UUID groupSetId, UUID productId, Integer sortOrder) {
        this.groupSetId = groupSetId;
        this.productId = productId;
        this.sortOrder = sortOrder;
    }
}
