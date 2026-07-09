package com.sapari.product.application.service;

import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.GetSellerProductsUseCase;
import com.sapari.product.view.ProductSummaryView;
import java.util.List;
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
     * 판매자의 삭제되지 않은 상품 요약 목록을 조회한다(프로젝션 쿼리 — 자식 미로딩).
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProductSummaryView> get(GetSellerProductsCommand command) {
        return productRepository.findActiveSellerProductSummaries(command.sellerId()).stream()
                .map(this::toView)
                .toList();
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
