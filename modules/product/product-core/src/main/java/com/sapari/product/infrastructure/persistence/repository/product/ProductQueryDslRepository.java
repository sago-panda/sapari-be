package com.sapari.product.infrastructure.persistence.repository.product;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.infrastructure.persistence.entity.product.QProductEntity;
import com.sapari.product.infrastructure.persistence.entity.product.QProductImageEntity;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 상품 애그리거트의 QueryDSL 조회 전용 어댑터. 복잡 프로젝션·동적 조회를 여기에 모은다
 * (Spring Data CRUD 래핑·자식 조립은 {@code ProductRepositoryImpl} 담당). {@code ProductRepositoryImpl}이 위임해 사용한다.
 */
@Repository
@RequiredArgsConstructor
public class ProductQueryDslRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 판매자의 삭제되지 않은 상품 요약 한 페이지를 프로젝션으로 조회한다(자식 미로딩). 스칼라 1쿼리 + 대표 이미지 배치 1쿼리로 N+1 없이 조립한다.
     *
     * <p>정렬 {@code created_at DESC, id DESC}와 keyset 조건 {@code created_at < :c OR (created_at = :c AND id < :id)}는 같은 두 키를
     * 같은 방향으로 쓴다 — 어긋나면 등록 일시가 같은 행이 페이지 경계에서 중복되거나 빠진다.
     *
     * @param sellerId       판매자 id
     * @param afterCreatedAt 이전 페이지 마지막 행의 등록 일시, 첫 페이지면 null
     * @param afterId        이전 페이지 마지막 행의 상품 id, 첫 페이지면 null
     * @param limit          최대 행 수
     * @return 정렬된 요약 목록(최대 limit개)
     */
    public List<ProductSummary> findActiveSellerProductSummaries(UUID sellerId, Instant afterCreatedAt, UUID afterId,
                                                                 int limit) {
        QProductEntity product = QProductEntity.productEntity;
        // 1) 요약 스칼라만 프로젝션 — 자식(옵션·조합·config) 미로딩, deleted_at IS NULL
        List<Tuple> rows = queryFactory
                .select(product.id, product.name, product.status, product.minPrice,
                        product.hasStock, product.reviewCount, product.avgRating, product.version, product.createdAt)
                .from(product)
                .where(product.sellerId.eq(sellerId), product.deletedAt.isNull(),
                        after(product, afterCreatedAt, afterId))
                .orderBy(product.createdAt.desc(), product.id.desc())   // 최신순, id로 tie-break(전순서)
                .limit(limit)
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        // 2) 대표 이미지 키를 상품 id IN 한 쿼리로 모아 조합(N+1 없이)
        List<UUID> productIds = rows.stream()
                .map(row -> row.get(product.id))
                .toList();
        Map<UUID, String> thumbnailByProduct = thumbnailKeysByProduct(productIds);
        return rows.stream()
                .map(row -> new ProductSummary(
                        row.get(product.id),
                        row.get(product.name),
                        row.get(product.status),
                        row.get(product.minPrice),
                        row.get(product.hasStock),
                        row.get(product.reviewCount),
                        row.get(product.avgRating),
                        thumbnailByProduct.get(row.get(product.id)),
                        row.get(product.version),
                        row.get(product.createdAt)))
                .toList();
    }

    /**
     * keyset 다음 위치 조건. 첫 페이지(위치 없음)면 null을 돌려 QueryDSL이 조건을 생략하게 한다.
     */
    private static BooleanExpression after(QProductEntity product, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null || afterId == null) {
            return null;
        }
        return product.createdAt.lt(afterCreatedAt)
                .or(product.createdAt.eq(afterCreatedAt).and(product.id.lt(afterId)));
    }

    /**
     * 상품별 대표 이미지 키(GALLERY 중 sortOrder 최소, 동률은 imageKey 최소)를 한 쿼리로 모아 맵으로 돌려준다.
     * DB에서 정렬해 가져온 뒤 상품별 첫 행을 채택({@code putIfAbsent})하므로 결정적이다. GALLERY가 없으면 맵에 없다(=null).
     */
    private Map<UUID, String> thumbnailKeysByProduct(List<UUID> productIds) {
        QProductImageEntity image = QProductImageEntity.productImageEntity;
        List<Tuple> rows = queryFactory
                .select(image.productId, image.imageKey)
                .from(image)
                .where(image.productId.in(productIds), image.imageRole.eq(ImageRole.GALLERY))
                .orderBy(image.sortOrder.asc(), image.imageKey.asc())
                .fetch();
        Map<UUID, String> thumbnailByProduct = new HashMap<>();
        for (Tuple row : rows) {
            thumbnailByProduct.putIfAbsent(row.get(image.productId), row.get(image.imageKey));
        }
        return thumbnailByProduct;
    }
}
