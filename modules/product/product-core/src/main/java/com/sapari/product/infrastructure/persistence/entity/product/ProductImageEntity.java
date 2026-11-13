package com.sapari.product.infrastructure.persistence.entity.product;

import com.sapari.product.domain.model.product.ImageRole;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품·옵션 이미지 통합(폴리모픽). 소유자 = product_id XOR option_value_id, image_role로 구분. (대표/상세/본문/색칩 분리 테이블 없음 — 단일 테이블)
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_images", schema = "product_schema")
public class ProductImageEntity extends BaseUuidEntity {

    // 기본(공유) 이미지 소유. option_value_id와 정확히 하나만 NOT NULL. 물리 FK 미사용.
    private UUID productId;

    // 옵션값(주로 색상) 종속 이미지. 물리 FK 미사용.
    private UUID optionValueId;

    @Enumerated(EnumType.STRING)
    private ImageRole imageRole;

    private String imageKey;

    private Short sortOrder;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     *
     * <p>폴리모픽 소유자라서 productId·optionValueId 중 정확히 하나만 채워 호출한다.
     */
    @Builder
    private ProductImageEntity(UUID productId, UUID optionValueId, ImageRole imageRole, String imageKey,
                               Short sortOrder) {
        this.productId = productId;
        this.optionValueId = optionValueId;
        this.imageRole = imageRole;
        this.imageKey = imageKey;
        this.sortOrder = sortOrder;
    }
}
