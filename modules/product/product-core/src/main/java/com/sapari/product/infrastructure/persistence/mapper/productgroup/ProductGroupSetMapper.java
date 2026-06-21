package com.sapari.product.infrastructure.persistence.mapper.productgroup;

import com.sapari.product.domain.model.productgroup.ProductGroupItemRef;
import com.sapari.product.domain.model.productgroup.ProductGroupSet;
import com.sapari.product.infrastructure.persistence.entity.productgroup.ProductGroupItemEntity;
import com.sapari.product.infrastructure.persistence.entity.productgroup.ProductGroupSetEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * {@link ProductGroupSet} 도메인 ↔ {@link ProductGroupSetEntity} 변환. 루트 평면 필드와 item VO는 MapStruct가 생성하고, items 컬렉션 조립은
 * RepositoryImpl이 담당하므로 {@code toDomain}에서는 ignore 한다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductGroupSetMapper {

    // items는 별도 테이블 — RepositoryImpl이 toItemRef로 조립해 채운다.
    @Mapping(target = "items", ignore = true)
    ProductGroupSet toDomain(ProductGroupSetEntity entity);

    ProductGroupSetEntity toEntity(ProductGroupSet domain);

    ProductGroupItemRef toItemRef(ProductGroupItemEntity entity);

    default void updateEntityFromDomain(ProductGroupSetEntity entity, ProductGroupSet domain) {
        entity.updateSellerId(domain.sellerId());
        entity.updateGroupName(domain.groupName());
    }
}
