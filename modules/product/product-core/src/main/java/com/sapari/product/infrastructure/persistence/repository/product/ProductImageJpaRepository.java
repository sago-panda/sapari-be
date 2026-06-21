package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.ProductImageEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 폴리모픽 상품 이미지(product_images) 리포지토리. 자식 replace는 productId 기준으로 동작하므로 옵션값(색상) 종속 이미지(optionValueId 소유)의 라이프사이클은 이 경로로
 * 관리되지 않는다.
 */
public interface ProductImageJpaRepository extends JpaRepository<ProductImageEntity, UUID> {
    List<ProductImageEntity> findByProductId(UUID productId);

    void deleteByProductId(UUID productId);
}
