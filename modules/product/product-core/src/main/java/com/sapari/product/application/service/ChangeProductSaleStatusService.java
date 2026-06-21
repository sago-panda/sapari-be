package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.ChangeSaleStatusCommand;
import com.sapari.product.domain.exception.InvalidProductStateException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.ChangeProductSaleStatusUseCase;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 상태 전환 유스케이스. {@code ON_SALE} ↔ {@code SUSPENDED}만 허용하며, 전이 가드는 도메인이 책임진다.
 */
@Service
@RequiredArgsConstructor
public class ChangeProductSaleStatusService implements ChangeProductSaleStatusUseCase {

    private final ProductRepository productRepository;
    private final TimeProvider timeProvider;

    /**
     * 상품 판매 상태를 전환한다. 존재·소유권을 확인하고 target에 따라 중지/재개한 뒤 저장한다.
     *
     * @throws ProductNotFoundException     상품이 없거나 삭제된 경우
     * @throws ProductAccessDeniedException 요청 판매자가 소유자가 아닌 경우
     * @throws InvalidProductStateException target이 허용 값이 아니거나 전이가 불가한 상태인 경우
     */
    @Override
    @Transactional
    public void change(ChangeSaleStatusCommand command) {
        Product product = productRepository.findById(command.productId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        if (!product.sellerId().equals(command.sellerId())) {
            throw new ProductAccessDeniedException(
                    "상품 소유자가 아닙니다: product=" + command.productId() + ", seller=" + command.sellerId());
        }

        productRepository.save(transition(product, command.target(), timeProvider.now()));
    }

    /**
     * target 상태명에 맞는 전이를 적용한다. SUSPENDED/ON_SALE 외 값은 허용하지 않는다.
     *
     * @throws InvalidProductStateException 허용되지 않는 target이거나 전이가 불가한 경우(도메인 가드)
     */
    private Product transition(Product product, String target, Instant now) {
        if (ProductStatus.SUSPENDED.name().equals(target)) {
            return product.suspend(now);
        }
        if (ProductStatus.ON_SALE.name().equals(target)) {
            return product.resume(now);
        }
        throw new InvalidProductStateException("허용되지 않는 판매 상태 전환 대상입니다: " + target);
    }
}
