package com.sapari.product.infrastructure.persistence.repository.product;

import com.sapari.product.infrastructure.persistence.entity.product.option.ProductOptionConfigEntity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 옵션 저작 룰(product_option_configs, 상품과 1:1) 리포지토리. PK가 productId라 별도 finder가 없다.
 */
public interface ProductOptionConfigJpaRepository extends JpaRepository<ProductOptionConfigEntity, UUID> {
}
