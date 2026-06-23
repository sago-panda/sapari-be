package com.sapari.product.infrastructure.persistence.repository.product;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.infrastructure.persistence.entity.product.QProductEntity;
import com.sapari.product.infrastructure.persistence.entity.product.QProductImageEntity;
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
     * 판매자의 삭제되지 않은 상품 요약 목록을 프로젝션으로 조회한다(자식 미로딩). 스칼라 1쿼리 + 대표 이미지 배치 1쿼리로 N+1 없이 조립한다.
     */
    public List<ProductSummary> findActiveSellerProductSummaries(UUID sellerId) {
        QProductEntity product = QProductEntity.productEntity;
        // 1) 요약 스칼라만 프로젝션 — 자식(옵션·조합·config) 미로딩, deleted_at IS NULL
        List<Tuple> rows = queryFactory
                .select(product.id, product.name, product.status, product.minPrice,
                        product.hasStock, product.reviewCount, product.avgRating)
                .from(product)
                .where(product.sellerId.eq(sellerId), product.deletedAt.isNull())
                .orderBy(product.createdAt.desc(), product.id.asc())   // 최신순, id로 tie-break(결정적)
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
                        thumbnailByProduct.get(row.get(product.id))))
                .toList();
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
