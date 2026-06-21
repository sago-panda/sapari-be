package com.sapari.product.infrastructure.persistence.repository.discount;

import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyCombinationEntity;
import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyEntity;
import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyProductEntity;

import com.sapari.product.domain.model.discount.DiscountPolicy;
import com.sapari.product.domain.repository.discount.DiscountPolicyRepository;
import com.sapari.product.infrastructure.persistence.mapper.discount.DiscountPolicyMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link DiscountPolicyRepository} 영속 어댑터.
 *
 * <p>정책 행 upsert + 대상 매핑(product/combination) delete-by-policyId 후 재삽입으로 통째 교체.
 * 조회 시 두 매핑 테이블에서 {@code productIds}/{@code combinationIds}를 조립해 도메인을 복원한다.
 */
@Repository
@RequiredArgsConstructor
public class DiscountPolicyRepositoryImpl implements DiscountPolicyRepository {

    private final DiscountPolicyJpaRepository jpaRepository;
    private final DiscountPolicyProductJpaRepository productJpaRepository;
    private final DiscountPolicyCombinationJpaRepository combinationJpaRepository;
    private final DiscountPolicyMapper mapper;

    @Override
    public DiscountPolicy save(DiscountPolicy policy) {
        DiscountPolicyEntity entity;
        if (policy.id() == null) {
            entity = jpaRepository.save(mapper.toEntity(policy));
        } else {
            entity = jpaRepository.findById(policy.id())
                    .orElseThrow(() -> new EntityNotFoundException("해당 할인 정책을 찾을 수 없습니다."));
            mapper.updateEntityFromDomain(entity, policy);
            entity = jpaRepository.save(entity);
        }
        replaceTargets(entity.getId(), policy.productIds(), policy.combinationIds());
        return loadDomain(entity);
    }

    @Override
    public Optional<DiscountPolicy> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(this::loadDomain);
    }

    @Override
    public List<DiscountPolicy> findAllActive() {
        return jpaRepository.findByIsActiveTrue()
                .stream()
                .map(this::loadDomain)
                .toList();
    }

    private void replaceTargets(UUID policyId, List<UUID> productIds, List<UUID> combinationIds) {
        productJpaRepository.deleteByDiscountPolicyId(policyId);
        combinationJpaRepository.deleteByDiscountPolicyId(policyId);
        // 삭제를 INSERT보다 먼저 DB에 반영 — 같은 (discount_policy_id, product_id|combination_id) 재삽입 시
        // unique 충돌 방지 (Hibernate는 한 flush 안에서 INSERT를 DELETE보다 먼저 실행한다)
        productJpaRepository.flush();
        if (productIds != null) {
            for (UUID productId : productIds) {
                productJpaRepository.save(DiscountPolicyProductEntity.builder()
                        .discountPolicyId(policyId)
                        .productId(productId)
                        .build());
            }
        }
        if (combinationIds != null) {
            for (UUID combinationId : combinationIds) {
                combinationJpaRepository.save(DiscountPolicyCombinationEntity.builder()
                        .discountPolicyId(policyId)
                        .combinationId(combinationId)
                        .build());
            }
        }
    }

    private DiscountPolicy loadDomain(
            DiscountPolicyEntity entity) {
        List<UUID> productIds = productJpaRepository.findByDiscountPolicyId(entity.getId())
                .stream()
                .map(DiscountPolicyProductEntity::getProductId)
                .toList();
        List<UUID> combinationIds = combinationJpaRepository.findByDiscountPolicyId(entity.getId())
                .stream()
                .map(DiscountPolicyCombinationEntity::getCombinationId)
                .toList();
        return mapper.toDomain(entity)
                .toBuilder()
                .productIds(productIds)
                .combinationIds(combinationIds)
                .build();
    }
}
