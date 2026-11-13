package com.sapari.product.infrastructure.persistence.entity.category;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카테고리별 권장 옵션 속성 그룹 매핑의 영속 엔티티 (table {@code category_option_attribute_groups}). 판매자 옵션 등록 화면 드롭박스 소스.
 * {@code categoryId}·{@code attributeGroupId}는 물리 FK 없이 id로만 참조한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "category_option_attribute_groups", schema = "product_schema")
public class CategoryOptionAttributeGroupEntity extends BaseUuidEntity {

    // ref: categories.id. 물리 FK 미사용.
    private Long categoryId;

    // ref: option_attribute_groups.id. 물리 FK 미사용.
    private UUID attributeGroupId;

    private Integer sortOrder;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 빌더로만 생성/재구성한다.
     */
    @Builder
    public CategoryOptionAttributeGroupEntity(Long categoryId, UUID attributeGroupId, Integer sortOrder) {
        this.categoryId = categoryId;
        this.attributeGroupId = attributeGroupId;
        this.sortOrder = sortOrder;
    }

    /**
     * 매핑 대상 카테고리 id를 변경한다.
     */
    public void updateCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * 권장 옵션 속성 그룹 id를 변경한다.
     */
    public void updateAttributeGroupId(UUID attributeGroupId) {
        this.attributeGroupId = attributeGroupId;
    }

    /**
     * 드롭박스 노출 순서를 변경한다.
     */
    public void updateSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
