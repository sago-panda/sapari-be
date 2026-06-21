package com.sapari.product.infrastructure.persistence.entity.product;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 태그. 검색·기획전 필터용. 상품당 최대 10개.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_tags", schema = "product_schema")
public class ProductTagEntity extends BaseUuidEntity {

    // ref: products.id. 물리 FK 미사용.
    private UUID productId;

    private String name;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     */
    @Builder
    private ProductTagEntity(UUID productId, String name) {
        this.productId = productId;
        this.name = name;
    }
}
