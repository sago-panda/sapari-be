package com.sapari.product.infrastructure.persistence.repository.combination;

import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationEntity;
import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationValueEntity;

import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.infrastructure.persistence.mapper.combination.ProductOptionCombinationMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link ProductOptionCombinationRepository} 영속 어댑터.
 *
 * <p>저장은 upsert(id==null→INSERT, 그 외 로드 후 mutator 갱신)이며, 옵션값 매핑은
 * {@code deleteByOptionCombinationId} 후 재삽입으로 통째 교체한다. 조회 시 매핑 테이블에서 {@code optionValueIds}를 조립해 도메인 모델을 복원한다.
 */
@Repository
@RequiredArgsConstructor
public class ProductOptionCombinationRepositoryImpl implements ProductOptionCombinationRepository {

    private final ProductOptionCombinationJpaRepository jpaRepository;
    private final ProductOptionCombinationValueJpaRepository valueJpaRepository;
    private final ProductOptionCombinationMapper mapper;

    @Override
    public ProductOptionCombination save(ProductOptionCombination combination) {
        ProductOptionCombinationEntity entity;
        if (combination.id() == null) {
            entity = jpaRepository.save(mapper.toEntity(combination));
        } else {
            entity = jpaRepository.findById(combination.id())
                    .orElseThrow(() -> new EntityNotFoundException("해당 옵션 조합을 찾을 수 없습니다."));
            mapper.updateEntityFromDomain(entity, combination);
            entity = jpaRepository.save(entity);
        }
        replaceValues(entity.getId(), combination.optionValueIds());
        return loadDomain(entity);
    }

    @Override
    public Optional<ProductOptionCombination> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(this::loadDomain);
    }

    @Override
    public List<ProductOptionCombination> findByProductId(UUID productId) {
        return jpaRepository.findByProductId(productId)
                .stream()
                .map(this::loadDomain)
                .toList();
    }

    @Override
    public Optional<ProductOptionCombination> findByProductIdAndSku(UUID productId, String sku) {
        return jpaRepository.findByProductIdAndSku(productId, sku)
                .map(this::loadDomain);
    }

    private void replaceValues(UUID combinationId, List<UUID> optionValueIds) {
        valueJpaRepository.deleteByOptionCombinationId(combinationId);
        // 삭제를 INSERT보다 먼저 DB에 반영 — 같은 (option_combination_id, option_value_id) 재삽입 시 unique 충돌 방지
        // (Hibernate는 한 flush 안에서 INSERT를 DELETE보다 먼저 실행한다)
        valueJpaRepository.flush();
        if (optionValueIds == null) {
            return;
        }
        for (UUID valueId : optionValueIds) {
            valueJpaRepository.save(ProductOptionCombinationValueEntity.builder()
                    .optionCombinationId(combinationId)
                    .optionValueId(valueId)
                    .build());
        }
    }

    private ProductOptionCombination loadDomain(
            ProductOptionCombinationEntity entity) {
        List<UUID> valueIds = valueJpaRepository.findByOptionCombinationId(entity.getId())
                .stream()
                .map(ProductOptionCombinationValueEntity::getOptionValueId)
                .toList();
        return mapper.toDomain(entity)
                .toBuilder()
                .optionValueIds(valueIds)
                .build();
    }
}
