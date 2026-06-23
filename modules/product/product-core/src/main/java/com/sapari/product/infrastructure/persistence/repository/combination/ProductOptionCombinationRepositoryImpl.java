package com.sapari.product.infrastructure.persistence.repository.combination;

import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationEntity;
import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationValueEntity;

import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.infrastructure.persistence.mapper.combination.ProductOptionCombinationMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
    public List<ProductOptionCombination> saveAll(List<ProductOptionCombination> combinations) {
        if (combinations.isEmpty()) {
            return List.of();
        }
        // 신규(INSERT) 전용 — id가 있으면 toEntity가 id를 버려 중복 INSERT되므로 차단
        combinations.forEach(combination -> {
            if (combination.id() != null) {
                throw new IllegalArgumentException(
                        "saveAll은 신규 조합만 지원합니다(id가 이미 존재): " + combination.id());
            }
        });

        // 1) 조합 행 일괄 INSERT
        List<ProductOptionCombinationEntity> savedEntities = jpaRepository.saveAll(
                combinations.stream()
                        .map(mapper::toEntity)
                        .toList());

        // 2) 생성된 조합 id에 옵션값 매핑을 모아 일괄 INSERT (saveAll은 입력 순서를 보존)
        List<ProductOptionCombinationValueEntity> valueEntities = new ArrayList<>();
        for (int i = 0; i < savedEntities.size(); i++) {
            UUID combinationId = savedEntities.get(i)
                    .getId();
            for (UUID valueId : combinations.get(i)
                    .optionValueIds()) {
                valueEntities.add(ProductOptionCombinationValueEntity.builder()
                        .optionCombinationId(combinationId)
                        .optionValueId(valueId)
                        .build());
            }
        }
        valueJpaRepository.saveAll(valueEntities);

        // 3) 재조회 없이 입력 도메인에 부여된 id만 채워 반환 (read-back N+1 방지)
        List<ProductOptionCombination> result = new ArrayList<>(savedEntities.size());
        for (int i = 0; i < savedEntities.size(); i++) {
            result.add(combinations.get(i)
                    .toBuilder()
                    .id(savedEntities.get(i)
                            .getId())
                    .build());
        }
        return result;
    }

    @Override
    public Optional<ProductOptionCombination> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(this::loadDomain);
    }

    @Override
    public List<ProductOptionCombination> findByProductId(UUID productId) {
        List<ProductOptionCombinationEntity> entities = jpaRepository.findByProductId(productId);
        if (entities.isEmpty()) {
            return List.of();
        }
        // 옵션값 매핑을 조합별 개별 조회(N+1) 대신 IN 한 쿼리로 모아 조합 id 기준 그룹핑
        List<UUID> combinationIds = entities.stream()
                .map(ProductOptionCombinationEntity::getId)
                .toList();
        Map<UUID, List<UUID>> valueIdsByCombination =
                valueJpaRepository.findByOptionCombinationIdIn(combinationIds)
                        .stream()
                        .collect(Collectors.groupingBy(
                                ProductOptionCombinationValueEntity::getOptionCombinationId,
                                Collectors.mapping(
                                        ProductOptionCombinationValueEntity::getOptionValueId,
                                        Collectors.toList())));
        return entities.stream()
                .map(entity -> mapper.toDomain(entity)
                        .toBuilder()
                        .optionValueIds(valueIdsByCombination.getOrDefault(entity.getId(), List.of()))
                        .build())
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
