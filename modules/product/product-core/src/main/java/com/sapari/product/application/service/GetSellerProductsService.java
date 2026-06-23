package com.sapari.product.application.service;

import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.GetSellerProductsUseCase;
import com.sapari.product.view.ProductSummaryView;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 상품 목록 조회 유스케이스. 소프트 삭제된 상품을 걸러내고 목록 카드용 요약 뷰로 변환한다.
 */
@Service
@RequiredArgsConstructor
public class GetSellerProductsService implements GetSellerProductsUseCase {

    private final ProductRepository productRepository;

    /**
     * 판매자의 삭제되지 않은 상품 요약 목록을 조회한다. 삭제 제외는 repository 쿼리({@code deleted_at IS NULL})가 담당한다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProductSummaryView> get(GetSellerProductsCommand command) {
        return productRepository.findActiveBySellerId(command.sellerId()).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 상품을 요약 뷰로 변환한다. 대표 이미지는 GALLERY 중 sortOrder가 가장 낮은 이미지의 키다.
     */
    private ProductSummaryView toSummary(Product product) {
        return new ProductSummaryView(
                product.id(),
                product.name(),
                product.status().name(),
                product.minPrice(),
                product.hasStock(),
                thumbnailKey(product),
                product.reviewCount(),
                product.avgRating());
    }

    /**
     * 대표 이미지 키를 고른다 — GALLERY 역할 중 sortOrder 최소값. 없으면 null.
     */
    private String thumbnailKey(Product product) {
        return product.images().stream()
                .filter(image -> image.role() == ImageRole.GALLERY)
                .min(Comparator.comparing(ProductImageRef::sortOrder))
                .map(ProductImageRef::imageKey)
                .orElse(null);
    }
}
