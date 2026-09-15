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
     * 상품의 조합을 판매여부와 무관하게 모두 은퇴({@code is_available=false})시킨다. 옵션 교체 시 기존 조합을 일괄 은퇴시키는 용도의 벌크 갱신이다.
     *
     * <p>이미 판매자가 단종한 조합까지 포함해 행을 잠그고 version을 올린다 — 옵션 교체 전에 읽은 조합으로 판매를 재개하는 동시·순차 PATCH가 version 불일치로
     * 거절되게 한다(판매불가 행만 건너뛰면 그 행의 재개가 교체 뒤에도 살아남는다). 옵션값 매핑은 보존한다(주문 이력 참조).
     * 단일 상품의 기본 조합 키({@code DEFAULT})는 은퇴 시 비워, 교체 뒤 새 기본 조합을 만들 수 있게 한다.
     *
     * @return 은퇴 처리된 행 수(이미 단종된 행 포함)
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
