package com.sapari.product.domain.repository.productgroup;

import com.sapari.product.domain.model.productgroup.ProductGroupSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 상품 그룹 애그리거트의 영속 포트. {@link #save}는 그룹 + 구성 아이템을 함께 upsert하고 (아이템은 교체 저장), {@link #findBySellerId}는 판매자별 그룹 목록을 반환한다.
 */
public interface ProductGroupSetRepository {

    ProductGroupSet save(ProductGroupSet productGroupSet);

    Optional<ProductGroupSet> findById(UUID id);

    List<ProductGroupSet> findBySellerId(UUID sellerId);
}
