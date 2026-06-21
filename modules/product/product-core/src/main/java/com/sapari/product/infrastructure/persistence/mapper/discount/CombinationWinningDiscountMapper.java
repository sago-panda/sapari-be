package com.sapari.product.infrastructure.persistence.mapper.discount;

import com.sapari.product.domain.model.discount.CombinationWinningDiscount;
import com.sapari.product.infrastructure.persistence.entity.discount.CombinationWinningDiscountEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * {@link CombinationWinningDiscount} 도메인 ↔ {@link CombinationWinningDiscountEntity} 변환 (PK=combinationId, 앱 주입). 평면
 * 5컬럼이라 MapStruct가 생성하고, 승자 갱신만 mutator 기반 default로 둔다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CombinationWinningDiscountMapper {

    CombinationWinningDiscount toDomain(CombinationWinningDiscountEntity entity);

    CombinationWinningDiscountEntity toEntity(CombinationWinningDiscount domain);

    /**
     * 엔티티는 setter가 아니라 updateWinner 뮤테이터라 in-place 갱신은 손으로 처리한다.
     */
    default void updateEntityFromDomain(CombinationWinningDiscountEntity entity, CombinationWinningDiscount domain) {
        entity.updateWinner(domain.policyId(), domain.discountAmount(), domain.finalPrice(), domain.resolvedAt());
    }
}
