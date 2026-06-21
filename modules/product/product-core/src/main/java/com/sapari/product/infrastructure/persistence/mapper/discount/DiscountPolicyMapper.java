package com.sapari.product.infrastructure.persistence.mapper.discount;

import com.sapari.product.domain.model.discount.DiscountPolicy;
import com.sapari.product.domain.model.discount.DiscountValue;
import com.sapari.product.infrastructure.persistence.entity.discount.DiscountPolicyEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * {@link DiscountPolicy} 도메인 ↔ {@link DiscountPolicyEntity} 변환.
 *
 * <p>{@code toEntity}는 도메인 {@link DiscountValue} VO를 엔티티의 {@code discountType}+{@code discountValue}
 * 두 컬럼으로 분해(MapStruct가 @Mapping으로 생성, VO null이면 null-safe). 역방향({@code toDomain})은 두 컬럼을 VO로 합치고 product/combination
 * 매핑은 RepositoryImpl이 채우므로 default로 둔다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DiscountPolicyMapper {

    @Mapping(target = "discountType", source = "discountValue.type")
    @Mapping(target = "discountValue", source = "discountValue.value")
    DiscountPolicyEntity toEntity(DiscountPolicy domain);

    /**
     * 두 컬럼을 DiscountValue VO로 합치고, 매핑 테이블(product/combination ids)은 RepositoryImpl이 조립한다.
     */
    default DiscountPolicy toDomain(DiscountPolicyEntity e) {
        if (e == null) {
            return null;
        }
        return DiscountPolicy.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .discountValue(new DiscountValue(e.getDiscountType(), e.getDiscountValue()))
                .priority(e.getPriority())
                .startedAt(e.getStartedAt())
                .endedAt(e.getEndedAt())
                .isActive(e.getIsActive())
                .createdBy(e.getCreatedBy())
                .productIds(List.of())
                .combinationIds(List.of())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    default void updateEntityFromDomain(DiscountPolicyEntity e, DiscountPolicy d) {
        e.updateInfo(d.name(), d.description());
        DiscountValue dv = d.discountValue();
        e.updateDiscount(dv == null ? null : dv.type(), dv == null ? null : dv.value());
        e.updatePriority(d.priority());
        e.updatePeriod(d.startedAt(), d.endedAt());
        e.updateActive(d.isActive());
    }
}
