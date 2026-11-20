package com.sapari.product.infrastructure.persistence.entity.product.option;

import com.sapari.storage.db.entity.UuidTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품별 옵션 타입 (색상, 사이즈 등). 계층 순서로 드롭박스 선택 흐름 결정. Product 애그리거트 내부 (저작 클러스터).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_option_types", schema = "product_schema")
public class ProductOptionTypeEntity extends UuidTimeEntity {

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    // ref: option_attribute_groups.id (NULL=완전 커스텀). 물리 FK 미사용.
    private UUID attributeGroupId;

    private String name;

    private Short sortOrder;

    private Instant deletedAt;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     */
    @Builder
    private ProductOptionTypeEntity(UUID productId, UUID attributeGroupId, String name, Short sortOrder,
                                    Instant deletedAt) {
        this.productId = productId;
        this.attributeGroupId = attributeGroupId;
        this.name = name;
        this.sortOrder = sortOrder;
        this.deletedAt = deletedAt;
    }

    /**
     * 옵션 타입명을 변경한다.
     */
    public void updateName(String name) {
        this.name = name;
    }

    /**
     * 드롭박스 선택 흐름을 결정하는 노출 순서를 변경한다.
     */
    public void updateSortOrder(Short sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * 소프트딜리트 시각을 설정/해제한다(물리 제거가 아닌 deletedAt 마킹).
     */
    public void updateDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
