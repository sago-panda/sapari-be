package com.sapari.product.domain.repository.product;

import com.sapari.product.domain.model.product.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Product 애그리거트(상품 + 태그·이미지·옵션 타입/값/config) 영속 포트.
 *
 * <p>조회는 루트와 자식을 함께 조립해 완전한 {@link Product}로 돌려주고, 저장은 루트뿐 아니라 자식 컬렉션까지
 * 한 번에 동기화한다. 구현(어댑터)은 infrastructure에 있으며, 트랜잭션 경계는 application 레이어가 소유한다.
 */
public interface ProductRepository {
    /**
     * 신규(id == null)면 INSERT, 기존이면 루트 갱신 + 자식 전체 replace 후, 저장된 애그리거트를 반환한다.
     */
    Product save(Product product);

    /**
     * id로 단일 상품을 자식까지 조립해 조회한다.
     */
    Optional<Product> findById(UUID id);

    /**
     * 판매자의 상품 목록(각각 자식 포함). 소프트삭제(deletedAt) 필터링은 호출측 책임.
     */
    List<Product> findBySellerId(UUID sellerId);

    /**
     * 판매자의 <b>삭제되지 않은</b> 상품 목록(각각 자식 포함). 쿼리에서 {@code deleted_at IS NULL}로 걸러
     * 삭제 상품을 로딩하지 않는다(목록 조회용).
     */
    List<Product> findActiveBySellerId(UUID sellerId);

    /**
     * 카테고리(bigint id) 소속 상품 목록.
     */
    List<Product> findByCategoryId(Long categoryId);
}
