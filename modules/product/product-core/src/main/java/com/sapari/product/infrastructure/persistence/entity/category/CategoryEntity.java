package com.sapari.product.infrastructure.persistence.entity.category;

import com.sapari.storage.db.entity.LongTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * 상품 카테고리 트리의 영속 엔티티 (table {@code categories}).
 *
 * <p>{@code id}는 ltree {@code path} 라벨로 직접 쓰여 bigint IDENTITY(DB 자동생성)이므로,
 * 시각 컬럼을 포함한 {@link LongTimeEntity}를 상속한다. 자기참조 {@code parentId}는 물리 FK 없이 트리를 구성한다. 상태 변경은 {@code updateXxx}
 * mutator로만(빌더는 신규 생성 전용).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "categories", schema = "product_schema")
public class CategoryEntity extends LongTimeEntity {

    // self ref (NULL=최상위). 물리 FK 미사용 — bare id 컬럼.
    private Long parentId;

    @ColumnTransformer(write = "?::ltree")
    private String path;

    private String name;

    private Short depth;

    private Integer sortOrder;

    private Boolean isActive;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 빌더로만 신규 생성/재구성한다(id는 DB IDENTITY 생성).
     */
    @Builder
    public CategoryEntity(Long parentId, String path, String name, Short depth, Integer sortOrder,
                          Boolean isActive) {
        this.parentId = parentId;
        this.path = path;
        this.name = name;
        this.depth = depth;
        this.sortOrder = sortOrder;
        this.isActive = isActive;
    }

    /**
     * 상위 카테고리 id를 변경한다(NULL=최상위, 트리 이동 시 사용).
     */
    public void updateParentId(Long parentId) {
        this.parentId = parentId;
    }

    /**
     * ltree path를 변경한다(트리 위치 이동 시 id 라벨로 구성된 경로를 재설정).
     */
    public void updatePath(String path) {
        this.path = path;
    }

    /**
     * 카테고리명을 변경한다.
     */
    public void updateName(String name) {
        this.name = name;
    }

    /**
     * 트리 깊이를 변경한다(트리 이동에 맞춰 path와 함께 조정).
     */
    public void updateDepth(Short depth) {
        this.depth = depth;
    }

    /**
     * 동일 계층 내 노출 순서를 변경한다.
     */
    public void updateSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * 카테고리 활성/비활성 상태를 변경한다.
     */
    public void updateIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
