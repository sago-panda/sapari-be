package com.sapari.product.infrastructure.persistence.mapper.product;

import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.infrastructure.persistence.entity.product.ProductEntity;
import com.sapari.product.infrastructure.persistence.entity.product.ProductImageEntity;
import com.sapari.product.infrastructure.persistence.entity.product.ProductTagEntity;
import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionConfigEntity;
import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionTypeEntity;
import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionValueEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Product 애그리거트(상품 + 태그/이미지/옵션 타입·값·config) ↔ 영속 엔티티 변환.
 *
 * <p>루트 {@code toEntity}(평면 스칼라 1:1, status·optionModel은 공유 enum 패스스루)는 MapStruct가 생성한다.
 * 자식은 별도 테이블이라, 별도 로드한 자식 리스트로 애그리거트를 조립하는 {@code toDomain}과 자식 엔티티 빌더, 그리고 mutator 기반 in-place 갱신은 {@code default}
 * 메서드가 담당한다(RepositoryImpl이 자식을 delete-replace).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductMapper {

    // ===== toEntity (루트만 — 자식은 Impl에서 빌더로 생성·저장) =====
    ProductEntity toEntity(Product product);

    // ===== toDomain (루트 + 별도 로드된 자식 조립) =====

    /**
     * 루트 엔티티와 별도로 로드된 자식 리스트들을 하나의 {@link Product} 애그리거트로 조립한다. 옵션 값은 {@code optionTypeId} 기준으로 그룹핑해 각 타입 아래에 중첩하고,
     * config가 없으면 {@code optionConfig}는 null.
     */
    default Product toDomain(
            ProductEntity entity,
            List<ProductTagEntity> tags,
            List<ProductImageEntity> images,
            List<ProductOptionTypeEntity> types,
            List<ProductOptionValueEntity> values,
            ProductOptionConfigEntity config) {
        if (entity == null) {
            return null;
        }

        Map<UUID, List<ProductOptionValueModel>> valuesByType = values.stream()
                .collect(Collectors.groupingBy(ProductOptionValueEntity::getOptionTypeId,
                        Collectors.mapping(this::toValueModel, Collectors.toList())));

        List<ProductOptionTypeModel> typeModels = types.stream()
                .map(t -> new ProductOptionTypeModel(t.getId(), t.getAttributeGroupId(), t.getName(),
                        t.getSortOrder(), valuesByType.getOrDefault(t.getId(), List.of())))
                .toList();

        return Product.builder()
                .id(entity.getId())
                .sellerId(entity.getSellerId())
                .categoryId(entity.getCategoryId())
                .name(entity.getName())
                .description(entity.getDescription())
                .basePrice(entity.getBasePrice())
                .status(entity.getStatus())
                .shippingPolicyId(entity.getShippingPolicyId())
                .additionalShippingFee(entity.getAdditionalShippingFee())
                .rejectionReason(entity.getRejectionReason())
                .deletedAt(entity.getDeletedAt())
                .minPrice(entity.getMinPrice())
                .hasStock(entity.getHasStock())
                .purchaseCount(entity.getPurchaseCount())
                .avgRating(entity.getAvgRating())
                .reviewCount(entity.getReviewCount())
                .viewCount(entity.getViewCount())
                .wishlistCount(entity.getWishlistCount())
                .optionModel(entity.getOptionModel())
                .searchIndexedAt(entity.getSearchIndexedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .tags(tags.stream()
                        .map(ProductTagEntity::getName)
                        .toList())
                .images(images.stream()
                        .map(this::toImageRef)
                        .toList())
                .optionTypes(typeModels)
                .optionConfig(config == null ? null
                        : new ProductOptionConfigModel(config.getSurchargeRules(), config.getExclusionRules()))
                .build();
    }

    default ProductImageRef toImageRef(ProductImageEntity e) {
        return new ProductImageRef(e.getId(), e.getProductId(), e.getOptionValueId(),
                e.getImageRole(), e.getImageKey(), e.getSortOrder());
    }

    default ProductOptionValueModel toValueModel(ProductOptionValueEntity e) {
        return new ProductOptionValueModel(e.getId(), e.getAttributePresetId(), e.getValue(),
                e.getMetadata(), e.getPriceDelta(), e.getSortOrder());
    }

    /**
     * 기존 엔티티의 루트 컬럼만 in-place 갱신한다(mutator 경유). 자식(태그/이미지/옵션/config)은 여기서 건드리지 않으며 {@code ProductRepositoryImpl}이
     * delete-by-productId 후 재삽입으로 교체한다.
     */
    default void updateEntityFromDomain(ProductEntity entity, Product d) {
        entity.updateBasicInfo(d.categoryId(), d.name(), d.description(), d.basePrice(),
                d.shippingPolicyId(), d.additionalShippingFee(), d.optionModel());
        entity.updateStatus(d.status(), d.rejectionReason());
        entity.updateDeletedAt(d.deletedAt());
        entity.updateCacheColumns(d.minPrice(), d.hasStock(), d.purchaseCount(), d.avgRating(),
                d.reviewCount(), d.viewCount(), d.wishlistCount());
        entity.updateSearchIndexedAt(d.searchIndexedAt());
    }

    // ===== 자식 엔티티 빌더 (RepositoryImpl이 사용) =====
    default ProductTagEntity toTagEntity(UUID productId, String name) {
        return ProductTagEntity.builder()
                .productId(productId)
                .name(name)
                .build();
    }

    default ProductImageEntity toImageEntity(ProductImageRef r) {
        return ProductImageEntity.builder()
                .productId(r.productId())
                .optionValueId(r.optionValueId())
                .imageRole(r.role())
                .imageKey(r.imageKey())
                .sortOrder(r.sortOrder())
                .build();
    }

    default ProductOptionTypeEntity toTypeEntity(UUID productId, ProductOptionTypeModel m) {
        return ProductOptionTypeEntity.builder()
                .productId(productId)
                .attributeGroupId(m.attributeGroupId())
                .name(m.name())
                .sortOrder(m.sortOrder())
                .build();
    }

    default ProductOptionValueEntity toValueEntity(UUID optionTypeId, ProductOptionValueModel m) {
        return ProductOptionValueEntity.builder()
                .optionTypeId(optionTypeId)
                .attributePresetId(m.attributePresetId())
                .value(m.value())
                .metadata(m.metadata())
                .priceDelta(m.priceDelta())
                .sortOrder(m.sortOrder())
                .build();
    }

    default ProductOptionConfigEntity toConfigEntity(UUID productId, ProductOptionConfigModel m) {
        return ProductOptionConfigEntity.builder()
                .productId(productId)
                .surchargeRules(m.surchargeRules())
                .exclusionRules(m.exclusionRules())
                .build();
    }
}
