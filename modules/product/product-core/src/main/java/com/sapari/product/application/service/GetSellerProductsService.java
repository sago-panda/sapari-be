package com.sapari.product.application.service;

import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.PageSupport;
import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.GetSellerProductsUseCase;
import com.sapari.product.view.ProductSummaryView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 상품 목록 조회 유스케이스. 요약 read-model을 받아 목록 카드 뷰로 변환한다. 삭제 제외·대표 이미지 선택은 repository 프로젝션 쿼리가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class GetSellerProductsService implements GetSellerProductsUseCase {

    private final ProductRepository productRepository;

    /**
     * 판매자의 삭제되지 않은 상품 요약을 최신 등록순으로 한 페이지 조회한다(프로젝션 쿼리 — 자식 미로딩).
     *
     * <p>커서는 마지막 행의 (등록 일시 epoch 마이크로초, 상품 id)다. DB {@code timestamptz} 정밀도가 마이크로초라 그 단위로 인코딩해야
     * 다음 페이지의 {@code created_at = :c} 비교가 정확히 맞는다.
     *
     * <p>손상되거나 형식이 맞지 않는 커서는 {@code CursorCodec}·{@code Cursor}가 {@code InvalidCursorException}(400)으로 거절한다.
     */
    @Override
    @Transactional(readOnly = true)
    public CursorPage<ProductSummaryView> get(GetSellerProductsCommand command) {
        int size = PageSupport.normalizeSize(command.size());
        Cursor cursor = (command.cursor() == null) ? null : CursorCodec.decode(command.cursor());
        Instant afterCreatedAt = (cursor == null) ? null : fromEpochMicros(cursor.sortKeyAsLong());
        UUID afterId = (cursor == null) ? null : cursor.idAsUuid();

        // size + 1개를 읽어 다음 페이지 존재 여부를 판정한다(CursorPage.of가 잉여 1개를 잘라낸다)
        List<ProductSummary> rows = productRepository.findActiveSellerProductSummaries(
                command.sellerId(), afterCreatedAt, afterId, size + 1);
        CursorPage<ProductSummary> page = CursorPage.of(rows, size,
                last -> CursorCodec.encode(String.valueOf(toEpochMicros(last.createdAt())), last.id().toString()));

        return new CursorPage<>(page.content().stream().map(this::toView).toList(), page.nextCursor(), page.hasNext());
    }

    /**
     * 등록 일시를 커서 정렬키(epoch 마이크로초)로 변환한다.
     */
    private static long toEpochMicros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    /**
     * 커서 정렬키(epoch 마이크로초)를 등록 일시로 복원한다.
     */
    private static Instant fromEpochMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    /**
     * 요약 read-model을 목록 뷰로 변환한다(상태 enum은 이름 문자열로 노출).
     */
    private ProductSummaryView toView(ProductSummary summary) {
        return new ProductSummaryView(
                summary.id(),
                summary.name(),
                summary.status().name(),
                summary.minPrice(),
                summary.hasStock(),
                summary.thumbnailKey(),
                summary.reviewCount(),
                summary.avgRating(),
                summary.version());
    }
}
