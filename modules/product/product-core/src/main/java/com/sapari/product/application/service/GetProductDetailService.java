package com.sapari.product.application.service;

import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.GetProductDetailUseCase;
import com.sapari.product.view.CombinationView;
import com.sapari.product.view.OptionTypeView;
import com.sapari.product.view.OptionValueView;
import com.sapari.product.view.ProductDetailView;
import com.sapari.product.view.ProductImageView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 상세 조회 유스케이스. 상품 애그리거트(기본 정보·태그·이미지·옵션 트리)와 별도 애그리거트인 옵션 조합을 함께 조립해 뷰로 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class GetProductDetailService implements GetProductDetailUseCase {

    private final ProductRepository productRepository;
    private final ProductOptionCombinationRepository combinationRepository;

    /**
     * 상품 상세를 조회한다. 소프트 삭제된 상품은 미존재로 간주한다.
     *
     * @throws ProductNotFoundException 상품이 없거나 삭제된 경우
     */
    @Override
    @Transactional(readOnly = true)
    public ProductDetailView get(GetProductDetailCommand command) {
        // 삭제된 상품은 없는 것으로 취급 — 조합 조회 전에 차단한다
        Product product = productRepository.findActiveById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        List<ProductOptionCombination> combinations = combinationRepository.findByProductId(product.id());

        return toView(product, combinations);
    }

    /**
     * 상품 애그리거트와 조합 목록을 상세 뷰로 조립한다.
     */
    private ProductDetailView toView(Product product, List<ProductOptionCombination> combinations) {
        return new ProductDetailView(
                product.id(),
                product.sellerId(),
                product.categoryId(),
                product.name(),
                product.description(),
                product.basePrice(),
                product.status().name(),
                product.minPrice(),
                product.hasStock(),
                product.shippingPolicyId(),
                product.additionalShippingFee(),
                product.avgRating(),
                product.reviewCount(),
                product.tags(),
                product.images().stream().map(this::toImageView).toList(),
                product.optionTypes().stream().map(this::toOptionTypeView).toList(),
                combinations.stream().map(this::toCombinationView).toList());
    }

    /**
     * 이미지 참조를 뷰로 변환한다(역할 enum은 이름 문자열로 노출).
     */
    private ProductImageView toImageView(ProductImageRef image) {
        return new ProductImageView(
                image.id(),
                image.role().name(),
                image.optionValueId(),
                image.imageKey(),
                image.sortOrder());
    }

    /**
     * 옵션 타입과 그 값들을 중첩 뷰로 변환한다.
     */
    private OptionTypeView toOptionTypeView(ProductOptionTypeModel type) {
        List<OptionValueView> values = type.values().stream()
                .map(value -> new OptionValueView(
                        value.id(),
                        value.value(),
                        value.metadata(),
                        value.priceDelta(),
                        value.sortOrder()))
                .toList();
        return new OptionTypeView(type.id(), type.name(), type.sortOrder(), values);
    }

    /**
     * 옵션 조합을 뷰로 변환한다 — 가용 재고는 도메인 계산값을, sku는 값 문자열을 노출한다.
     */
    private CombinationView toCombinationView(ProductOptionCombination combination) {
        Sku sku = combination.sku();
        return new CombinationView(
                combination.id(),
                combination.combinationKey().value(),
                combination.optionValueIds(),
                combination.price(),
                combination.originalPrice(),
                combination.stock().stock(),
                combination.availableStock(),
                combination.isAvailable(),
                sku == null ? null : sku.value());
    }
}
