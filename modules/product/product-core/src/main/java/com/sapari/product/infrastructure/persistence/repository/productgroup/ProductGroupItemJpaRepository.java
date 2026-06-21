package com.sapari.product.infrastructure.persistence.repository.productgroup;

import com.sapari.product.infrastructure.persistence.entity.productgroup.ProductGroupItemEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@code product_group_items} JPA 리포지토리. 그룹 단위 정렬 조회 + 교체 저장용 삭제 제공.
 */
@Repository
public interface ProductGroupItemJpaRepository extends JpaRepository<ProductGroupItemEntity, UUID> {

    List<ProductGroupItemEntity> findByGroupSetIdOrderBySortOrderAsc(UUID groupSetId);

    void deleteByGroupSetId(UUID groupSetId);
}
