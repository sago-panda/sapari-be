package com.sapari.product.domain.repository.combination;

import com.sapari.product.domain.model.combination.ProductOptionCombination;
import java.time.Instant;
import java.util.Collection;
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
     * 주어진 id들의 조합을 일괄 조회한다(개별 {@link #findById} 반복으로 인한 N+1 방지). 존재하는 것만 반환하며 순서는 보장하지 않는다.
     */
    List<ProductOptionCombination> findAllById(Collection<UUID> ids);

    /**
     * 상품의 판매가능 조합을 모두 단종({@code is_available=false})한다. 옵션 교체 시 기존 조합을 일괄 은퇴시키는 용도의 벌크 갱신이다.
     *
     * <p>벌크 UPDATE라 {@code @Version}을 증가시키지 않고 영속성 컨텍스트를 우회한다(전량 은퇴라 동시성 보호 불필요). 옵션값 매핑은 보존한다(주문 이력 참조).
     *
     * @return 단종 처리된 행 수
     */
    int discontinueAllByProductId(UUID productId, Instant now);

    /**
     * 상품의 모든 조합. 상품 상세·옵션 트리 구성용.
     */
    List<ProductOptionCombination> findByProductId(UUID productId);

    /**
     * 상품 범위 내 유일한 SKU로 조회(WMS/3PL 연동).
     */
    Optional<ProductOptionCombination> findByProductIdAndSku(UUID productId, String sku);
}
