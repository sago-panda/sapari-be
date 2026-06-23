package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.ProductEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 원장(products) Spring Data 리포지토리 — 루트 엔티티 전용. 자식(태그·이미지·옵션)까지의 애그리거트 조립/해체는 {@link ProductRepositoryImpl}가 담당한다.
 */
public interface ProductJpaRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findByCategoryId(Long categoryId);
}
