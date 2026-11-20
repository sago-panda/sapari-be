package com.sapari.product.infrastructure.persistence.mapper.optionattribute;

import com.sapari.product.domain.model.optionattribute.OptionAttributeGroup;
import com.sapari.product.domain.model.optionattribute.OptionPreset;
import com.sapari.product.infrastructure.persistence.entity.optionattribute.OptionAttributeGroupEntity;
import com.sapari.product.infrastructure.persistence.entity.optionattribute.OptionAttributeGroupPresetEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * {@link OptionAttributeGroup} 도메인 ↔ {@link OptionAttributeGroupEntity} 변환. 루트 평면 필드와 preset VO는 MapStruct가 생성하고,
 * presets 컬렉션 조립은 RepositoryImpl이 담당하므로 {@code toDomain}에서는 ignore 한다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OptionAttributeGroupMapper {

    // presets는 별도 테이블 — RepositoryImpl이 toPreset으로 조립해 채운다.
    @Mapping(target = "presets", ignore = true)
    OptionAttributeGroup toDomain(OptionAttributeGroupEntity entity);

    OptionAttributeGroupEntity toEntity(OptionAttributeGroup domain);

    OptionPreset toPreset(OptionAttributeGroupPresetEntity entity);

    default void updateEntityFromDomain(OptionAttributeGroupEntity entity, OptionAttributeGroup domain) {
        entity.updateName(domain.name());
        entity.updateIsSystem(domain.isSystem());
        entity.updateSellerId(domain.sellerId());
        entity.updateDescription(domain.description());
    }
}
