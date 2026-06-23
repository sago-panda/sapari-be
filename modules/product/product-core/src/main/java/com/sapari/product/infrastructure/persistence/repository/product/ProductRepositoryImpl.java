package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.ProductEntity;
import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionTypeEntity;
import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionValueEntity;

import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.infrastructure.persistence.mapper.product.ProductMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Product 애그리거트 영속 어댑터. 루트 upsert + 자식(tags/images/options/config) replace. 트랜잭션 경계는 application 레이어가 소유한다(여기서는 선언하지
 * 않음).
 */
@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;
    private final ProductTagJpaRepository tagJpaRepository;
    private final ProductImageJpaRepository imageJpaRepository;
    private final ProductOptionTypeJpaRepository optionTypeJpaRepository;
    private final ProductOptionValueJpaRepository optionValueJpaRepository;
    private final ProductOptionConfigJpaRepository optionConfigJpaRepository;
    private final ProductMapper mapper;

    @Override
    public Product save(Product product) {
        ProductEntity entity;
        if (product.id() == null) {
            entity = productJpaRepository.save(mapper.toEntity(product));
        } else {
            entity = productJpaRepository.findById(product.id())
                    .orElseThrow(() -> new EntityNotFoundException("상품을 찾을 수 없습니다: " + product.id()));
            mapper.updateEntityFromDomain(entity, product);
            entity = productJpaRepository.save(entity);
        }
        replaceChildren(entity.getId(), product);
        return loadAggregate(entity);
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return productJpaRepository.findById(id)
                .map(this::loadAggregate);
    }

    @Override
    public List<Product> findBySellerId(UUID sellerId) {
        return productJpaRepository.findBySellerId(sellerId)
                .stream()
                .map(this::loadAggregate)
                .toList();
    }

    @Override
    public List<Product> findActiveBySellerId(UUID sellerId) {
        return productJpaRepository.findBySellerIdAndDeletedAtIsNull(sellerId)
                .stream()
                .map(this::loadAggregate)
                .toList();
    }

    @Override
    public List<Product> findByCategoryId(Long categoryId) {
        return productJpaRepository.findByCategoryId(categoryId)
                .stream()
                .map(this::loadAggregate)
                .toList();
    }

    /**
     * 자식 컬렉션을 단순 replace(전체 삭제 후 재삽입)로 동기화한다.
     */
    private void replaceChildren(UUID productId, Product product) {
        tagJpaRepository.deleteByProductId(productId);
        if (!product.tags()
                .isEmpty()) {
            tagJpaRepository.saveAll(product.tags()
                    .stream()
                    .map(name -> mapper.toTagEntity(productId, name))
                    .toList());
        }

        imageJpaRepository.deleteByProductId(productId);
        if (!product.images()
                .isEmpty()) {
            imageJpaRepository.saveAll(product.images()
                    .stream()
                    .map(mapper::toImageEntity)
                    .toList());
        }

        List<ProductOptionTypeEntity> existingTypes = optionTypeJpaRepository.findByProductId(productId);
        if (!existingTypes.isEmpty()) {
            optionValueJpaRepository.deleteByOptionTypeIdIn(
                    existingTypes.stream()
                            .map(ProductOptionTypeEntity::getId)
                            .toList());
            optionTypeJpaRepository.deleteByProductId(productId);
            // 삭제를 INSERT보다 먼저 DB에 반영한다. Hibernate는 한 flush 안에서 INSERT를 DELETE보다 먼저 실행하므로,
            // 동일 (product_id, name) 옵션 타입을 교체하면 부분 유니크(deleted_at IS NULL) 제약과 충돌한다.
            optionTypeJpaRepository.flush();
        }
        for (ProductOptionTypeModel typeModel : product.optionTypes()) {
            ProductOptionTypeEntity savedType = optionTypeJpaRepository.save(mapper.toTypeEntity(productId, typeModel));
            if (!typeModel.values()
                    .isEmpty()) {
                optionValueJpaRepository.saveAll(typeModel.values()
                        .stream()
                        .map(v -> mapper.toValueEntity(savedType.getId(), v))
                        .toList());
            }
        }

        if (product.optionConfig() != null) {
            optionConfigJpaRepository.save(mapper.toConfigEntity(productId, product.optionConfig()));
        } else if (optionConfigJpaRepository.existsById(productId)) {
            optionConfigJpaRepository.deleteById(productId);
        }
    }

    private Product loadAggregate(ProductEntity entity) {
        UUID id = entity.getId();
        List<ProductOptionTypeEntity> types = optionTypeJpaRepository.findByProductId(id);
        List<UUID> typeIds = types.stream()
                .map(ProductOptionTypeEntity::getId)
                .toList();
        List<ProductOptionValueEntity> values =
                typeIds.isEmpty() ? List.of() : optionValueJpaRepository.findByOptionTypeIdIn(typeIds);
        return mapper.toDomain(
                entity,
                tagJpaRepository.findByProductId(id),
                imageJpaRepository.findByProductId(id),
                types,
                values,
                optionConfigJpaRepository.findById(id)
                        .orElse(null));
    }
}
