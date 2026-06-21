package com.sapari.product.infrastructure.persistence.repository.combination;

import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationValueEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code product_option_combination_values}(조합↔옵션값 매핑) Spring Data JPA 어댑터. RepositoryImpl이 조합 저장 시
 * {@code deleteByOptionCombinationId} 후 재삽입하는 교체 전략에 사용한다.
 */
public interface ProductOptionCombinationValueJpaRepository
        extends JpaRepository<ProductOptionCombinationValueEntity, UUID> {

    List<ProductOptionCombinationValueEntity> findByOptionCombinationId(UUID optionCombinationId);

    void deleteByOptionCombinationId(UUID optionCombinationId);
}
