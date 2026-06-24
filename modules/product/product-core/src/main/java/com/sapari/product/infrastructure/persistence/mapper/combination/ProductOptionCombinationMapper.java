package com.sapari.product.infrastructure.persistence.mapper.combination;

import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.infrastructure.persistence.entity.combination.ProductOptionCombinationEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * {@link ProductOptionCombination} 도메인 ↔ {@link ProductOptionCombinationEntity} 변환.
 *
 * <p>{@code toEntity}는 VO를 컬럼으로 분해(MapStruct @Mapping, VO null이면 null-safe): {@code Sku}/{@code
 * CombinationKey}→문자열, {@code Stock}→{@code stock}+{@code reservedStock}. 역방향({@code toDomain})은 컬럼을 VO로 감싸고
 * optionValueIds(매핑 테이블)는 RepositoryImpl이 채우므로 default로 둔다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductOptionCombinationMapper {

    @Mapping(target = "sku", source = "sku.value")
    @Mapping(target = "combinationKey", source = "combinationKey.value")
    @Mapping(target = "stock", source = "stock.stock")
    @Mapping(target = "reservedStock", source = "stock.reservedStock")
    ProductOptionCombinationEntity toEntity(ProductOptionCombination domain);

    /**
     * 컬럼을 Sku/CombinationKey/Stock VO로 감싼다. optionValueIds(매핑 테이블)는 RepositoryImpl이 조립한다.
     */
    default ProductOptionCombination toDomain(ProductOptionCombinationEntity e) {
        if (e == null) {
            return null;
        }
        return ProductOptionCombination.builder()
                .id(e.getId())
                .version(e.getVersion())
                .productId(e.getProductId())
                .sku(Sku.of(e.getSku()))
                .combinationKey(e.getCombinationKey() == null ? null : new CombinationKey(e.getCombinationKey()))
                .originalPrice(e.getOriginalPrice())
                .price(e.getPrice())
                .stock(new Stock(e.getStock(), e.getReservedStock()))
                .isAvailable(e.getIsAvailable())
                .optionValueIds(List.of())
                .searchIndexedAt(e.getSearchIndexedAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    default void updateEntityFromDomain(ProductOptionCombinationEntity e, ProductOptionCombination d) {
        e.updateSku(d.sku() == null ? null : d.sku()
                .value());
        e.updateCombinationKey(d.combinationKey() == null ? null : d.combinationKey()
                .value());
        e.updatePrice(d.originalPrice(), d.price());
        e.updateStock(d.stock() == null ? null : d.stock()
                .stock(), d.stock() == null ? null : d.stock()
                .reservedStock());
        e.updateAvailability(d.isAvailable());
        e.updateSearchIndexedAt(d.searchIndexedAt());
    }
}
