package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionValueEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 옵션 값(product_option_values) 리포지토리. 옵션 타입 id 집합 기준 일괄 조회·삭제를 제공해 타입→값 2단 조립과 옵션 재저작 시의 cascade replace를 지원한다.
 */
public interface ProductOptionValueJpaRepository extends JpaRepository<ProductOptionValueEntity, UUID> {
    List<ProductOptionValueEntity> findByOptionTypeId(UUID optionTypeId);

    List<ProductOptionValueEntity> findByOptionTypeIdIn(Collection<UUID> optionTypeIds);

    void deleteByOptionTypeIdIn(Collection<UUID> optionTypeIds);
}
