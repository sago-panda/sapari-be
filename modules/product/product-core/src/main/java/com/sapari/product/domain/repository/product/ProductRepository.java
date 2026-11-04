package com.sapari.product.domain.repository.product;

import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductSummary;
import java.time.Instant;
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
     * 옵션 config(추가금·제외 룰 jsonb) 행<b>만</b> 교체한다. {@code config}가 null이면 행을 삭제한다.
     *
     * <p>룰은 저장 후에야 확정되는 옵션값 id를 참조하므로 상품 저장 뒤에 따로 기록해야 한다. {@link #save}를 다시 부르면
     * 옵션 타입/값이 delete-replace되어 id가 재발급되고 조합이 가리키는 옵션값이 사라지므로, 이 전용 메서드를 쓴다.
     *
     * @param productId 소속 상품 id(config PK)
     * @param config    교체할 룰, 없으면 null
     */
    void replaceOptionConfig(UUID productId, ProductOptionConfigModel config);

    /**
     * 상품의 조합 파생 캐시(최저가 {@code min_price}·재고유무 {@code has_stock})를 조합 테이블에서 다시 계산한다. 조합을 만들거나 가격·재고·판매여부를 바꾼
     * 트랜잭션의 끝에서 호출한다.
     *
     * <p>대기 중인 변경을 먼저 DB에 반영하고 상품 행을 잠근 뒤 새 문장으로 계산한다 — 같은 상품의 조합을 동시에 바꾸는 트랜잭션은 이 잠금에서 직렬화되고,
     * 뒤 트랜잭션은 앞 트랜잭션의 커밋된 조합까지 반영해 계산한다(잠금 순서: 조합 행 → 상품 행). 이 논리는 READ COMMITTED(PostgreSQL 기본)를 전제한다.
     *
     * <p>상품 version은 올리지 않는다(판매자 수정 폼의 expectedVersion을 무효화하지 않도록). updated_at은 {@code now}로 갱신한다 — 검색 재색인이
     * {@code search_indexed_at < updated_at}으로 대상을 고르므로, 재고·가격 캐시 변경이 재색인에 잡혀야 한다.
     *
     * @param productId 상품 id
     * @param now       갱신 시각(TimeProvider)
     */
    void refreshStockPriceCache(UUID productId, Instant now);

    /**
     * id로 단일 상품을 자식까지 조립해 조회한다(삭제 포함).
     */
    Optional<Product> findById(UUID id);

    /**
     * id로 <b>삭제되지 않은</b> 상품을 자식까지 조립해 조회한다. 쿼리에서 {@code deleted_at IS NULL}로 걸러
     * 삭제 상품은 빈 결과로 돌려준다(수정·삭제·상세 조회 등 활성 상품 대상). 삭제 상품은 자식도 로딩하지 않는다.
     */
    Optional<Product> findActiveById(UUID id);

    /**
     * 판매자의 삭제되지 않은 상품 <b>요약</b> 목록(목록 카드용). 프로젝션 쿼리로 스칼라 + 대표 이미지 키만 조회하고
     * 옵션·조합 등 자식은 로딩하지 않는다({@code deleted_at IS NULL} 필터 포함).
     *
     * <p>정렬은 등록 일시 내림차순, 동률은 id 내림차순(전순서). {@code afterCreatedAt}·{@code afterId}가 주어지면 그 위치 <b>다음</b>
     * 행부터 돌려준다(keyset). 둘은 함께 주거나 함께 null이어야 한다(null = 첫 페이지).
     *
     * @param sellerId       판매자 id
     * @param afterCreatedAt 이전 페이지 마지막 행의 등록 일시, 첫 페이지면 null
     * @param afterId        이전 페이지 마지막 행의 상품 id, 첫 페이지면 null
     * @param limit          최대 행 수(호출측이 다음 페이지 판정용으로 size + 1을 넘긴다)
     * @return 정렬된 요약 목록(최대 limit개)
     */
    List<ProductSummary> findActiveSellerProductSummaries(UUID sellerId, Instant afterCreatedAt, UUID afterId, int limit);
}
