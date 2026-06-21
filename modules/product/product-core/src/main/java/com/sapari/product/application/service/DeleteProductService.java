package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.DeleteProductCommand;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.DeleteProductUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 삭제 유스케이스. 소유권을 확인하고 논리 삭제(deletedAt 기록)한다. 되돌리기 불가.
 */
@Service
@RequiredArgsConstructor
public class DeleteProductService implements DeleteProductUseCase {

    private final ProductRepository productRepository;
    private final TimeProvider timeProvider;

    /**
     * 상품을 논리 삭제한다. 이미 삭제됐거나 없는 상품은 미존재로 취급한다.
     *
     * @throws ProductNotFoundException     상품이 없거나 이미 삭제된 경우
     * @throws ProductAccessDeniedException 요청 판매자가 소유자가 아닌 경우
     */
    @Override
    @Transactional
    public void delete(DeleteProductCommand command) {
        Product product = productRepository.findById(command.productId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        if (!product.sellerId().equals(command.sellerId())) {
            throw new ProductAccessDeniedException(
                    "상품 소유자가 아닙니다: product=" + command.productId() + ", seller=" + command.sellerId());
        }

        productRepository.save(product.softDelete(timeProvider.now()));
    }
}
