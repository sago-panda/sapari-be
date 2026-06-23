package com.sapari.product.domain.repository.combination;

import com.sapari.product.domain.model.combination.ProductOptionCombination;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProductOptionCombination} 애그리거트의 영속 포트.
 *
 * <p>조합은 재고·가격의 런타임 단위로 Product와 분리된 별도 애그리거트 루트다(주문·재고 선예약이
 * combination id로 직접 참조). 구현체는 조합 행과 그 옵션값 매핑({@code optionValueIds})을 함께 다룬다.
 */
public interface ProductOptionCombinationRepository {

    /**
     * 신규(id=null)면 INSERT, 기존이면 갱신하는 upsert. 옵션값 매핑도 함께 교체 저장하고, 저장된 조합을 반환한다.
     */
    ProductOptionCombination save(ProductOptionCombination combination);

    /**
     * 신규 조합을 일괄 저장하고 id만 채워 입력 순서대로 반환한다(재조회 없음).
     * JDBC 배치는 {@code hibernate.jdbc.batch_size} 설정 시 동작한다.
     */
    List<ProductOptionCombination> saveAll(List<ProductOptionCombination> combinations);

    Optional<ProductOptionCombination> findById(UUID id);

    /**
     * 상품의 모든 조합. 상품 상세·옵션 트리 구성용.
     */
    List<ProductOptionCombination> findByProductId(UUID productId);

    /**
     * 상품 범위 내 유일한 SKU로 조회(WMS/3PL 연동).
     */
    Optional<ProductOptionCombination> findByProductIdAndSku(UUID productId, String sku);
}
