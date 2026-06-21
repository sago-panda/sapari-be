package com.sapari.product.infrastructure.persistence.repository.discount;

import com.sapari.product.infrastructure.persistence.entity.discount.CombinationWinningDiscountEntity;

import com.sapari.product.domain.model.discount.CombinationWinningDiscount;
import com.sapari.product.domain.repository.discount.CombinationWinningDiscountRepository;
import com.sapari.product.infrastructure.persistence.mapper.discount.CombinationWinningDiscountMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link CombinationWinningDiscountRepository} 영속 어댑터.
 *
 * <p>PK(combinationId)가 앱 주입이라 일반 upsert와 달리 findById 존재 여부로 갱신/신규를 가른다(파생 캐시 재해소 경로).
 */
@Repository
@RequiredArgsConstructor
public class CombinationWinningDiscountRepositoryImpl implements CombinationWinningDiscountRepository {

    private final CombinationWinningDiscountJpaRepository jpaRepository;
    private final CombinationWinningDiscountMapper mapper;

    @Override
    public CombinationWinningDiscount save(CombinationWinningDiscount winningDiscount) {
        // PK(combinationId)는 앱 주입 — 존재하면 갱신, 없으면 신규.
        CombinationWinningDiscountEntity entity =
                jpaRepository.findById(winningDiscount.combinationId())
                        .map(existing -> {
                            mapper.updateEntityFromDomain(existing, winningDiscount);
                            return existing;
                        })
                        .orElseGet(() -> mapper.toEntity(winningDiscount));
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<CombinationWinningDiscount> findById(UUID combinationId) {
        return jpaRepository.findById(combinationId)
                .map(mapper::toDomain);
    }

    @Override
    public List<CombinationWinningDiscount> findByPolicyId(UUID policyId) {
        return jpaRepository.findByPolicyId(policyId)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
