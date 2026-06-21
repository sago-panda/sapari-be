package com.sapari.product.infrastructure.persistence.repository.search;

import com.sapari.product.infrastructure.persistence.entity.search.ProductSearchLogEntity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 검색 로그 Spring Data 어댑터(bigint app-TSID). 키워드 조회.
 */
public interface ProductSearchLogJpaRepository extends JpaRepository<ProductSearchLogEntity, Long> {
    List<ProductSearchLogEntity> findByKeyword(String keyword);
}
