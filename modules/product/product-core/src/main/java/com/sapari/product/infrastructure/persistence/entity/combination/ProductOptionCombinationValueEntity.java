package com.sapari.product.infrastructure.persistence.entity.combination;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 옵션 조합 ↔ 옵션 값 매핑. 조합 해석 및 역조회 용도.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_option_combination_values", schema = "product_schema")
public class ProductOptionCombinationValueEntity extends BaseUuidEntity {

    // ref: product_option_combinations.id. 물리 FK 미사용.
    private UUID optionCombinationId;

    // ref: product_option_values.id. 물리 FK 미사용.
    private UUID optionValueId;

    /**
     * 빌더 전용 생성자. JPA용 protected 기본 생성자와 구분해 private으로 두고 빌더로만 생성/재구성한다.
     */
    @Builder
    private ProductOptionCombinationValueEntity(UUID optionCombinationId, UUID optionValueId) {
        this.optionCombinationId = optionCombinationId;
        this.optionValueId = optionValueId;
    }
}
