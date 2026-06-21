package com.sapari.product.infrastructure.persistence.repository.productgroup;
import com.sapari.product.infrastructure.persistence.entity.productgroup.ProductGroupItemEntity;
import com.sapari.product.infrastructure.persistence.entity.productgroup.ProductGroupSetEntity;

import com.sapari.product.domain.model.productgroup.ProductGroupItemRef;
import com.sapari.product.domain.model.productgroup.ProductGroupSet;
import com.sapari.product.domain.repository.productgroup.ProductGroupSetRepository;
import com.sapari.product.infrastructure.persistence.mapper.productgroup.ProductGroupSetMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link ProductGroupSetRepository} 영속 어댑터. {@code save}는 그룹 upsert + 구성 아이템 <b>교체 저장</b>
 * (갱신 시 기존 아이템 전체 삭제 후 재삽입), 조회는 그룹 + 정렬된 아이템을 합쳐 복원한다.
 */
@Repository
@RequiredArgsConstructor
public class ProductGroupSetRepositoryImpl implements ProductGroupSetRepository {

  private final ProductGroupSetJpaRepository groupSetJpaRepository;
  private final ProductGroupItemJpaRepository groupItemJpaRepository;
  private final ProductGroupSetMapper mapper;

  @Override
  public ProductGroupSet save(ProductGroupSet domain) {
    UUID groupSetId;
    if (domain.id() == null) {
      var saved = groupSetJpaRepository.save(mapper.toEntity(domain));
      groupSetId = saved.getId();
    } else {
      var existing = groupSetJpaRepository.findById(domain.id())
          .orElseThrow(() -> new EntityNotFoundException("상품 그룹을 찾을 수 없습니다: " + domain.id()));
      mapper.updateEntityFromDomain(existing, domain);
      groupSetJpaRepository.save(existing);
      groupItemJpaRepository.deleteByGroupSetId(domain.id());
      // 삭제를 INSERT보다 먼저 DB에 반영 — 같은 (group_set_id, product_id) 재삽입 시 unique 충돌 방지
      // (Hibernate는 한 flush 안에서 INSERT를 DELETE보다 먼저 실행한다)
      groupItemJpaRepository.flush();
      groupSetId = domain.id();
    }
    persistItems(groupSetId, domain.items());
    return findById(groupSetId).orElseThrow();
  }

  @Override
  public Optional<ProductGroupSet> findById(UUID id) {
    return groupSetJpaRepository.findById(id).map(this::assemble);
  }

  @Override
  public List<ProductGroupSet> findBySellerId(UUID sellerId) {
    return groupSetJpaRepository.findBySellerId(sellerId).stream().map(this::assemble).toList();
  }

  private void persistItems(UUID groupSetId, List<ProductGroupItemRef> items) {
    for (ProductGroupItemRef item : items) {
      groupItemJpaRepository.save(ProductGroupItemEntity.builder()
          .groupSetId(groupSetId)
          .productId(item.productId())
          .sortOrder(item.sortOrder())
          .build());
    }
  }

  private ProductGroupSet assemble(
      ProductGroupSetEntity entity) {
    List<ProductGroupItemRef> items = groupItemJpaRepository
        .findByGroupSetIdOrderBySortOrderAsc(entity.getId()).stream()
        .map(mapper::toItemRef)
        .toList();
    return mapper.toDomain(entity).toBuilder().items(items).build();
  }
}
