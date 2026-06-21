package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.InvalidProductTagException;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductTagPolicy;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.view.CreateProductView;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 등록 유스케이스. 상품(옵션 타입/값 포함)을 먼저 저장해 옵션값 id를 확정한 뒤, 그 id로 옵션 조합을 생성·저장한다.
 *
 * <p>Product와 옵션 조합은 별도 애그리거트라 한 트랜잭션 안에서 2단계로 저장한다. 이미지·추가금/제외 룰·조합별 오버라이드는 후속 증분에서 추가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateProductService implements CreateProductUseCase {

    private final ProductRepository productRepository;
    private final ProductOptionCombinationRepository combinationRepository;
    private final CategoryRepository categoryRepository;
    private final TimeProvider timeProvider;

    /**
     * 상품을 등록한다. 태그·카테고리를 검증하고, 상품을 검수 대기 상태로 저장한 뒤 옵션 조합을 자동 생성·저장한다.
     *
     * @throws InvalidProductTagException 태그 규칙 위반(개수·길이·특수문자)인 경우
     * @throws CategoryNotFoundException  존재하지 않는 카테고리인 경우
     */
    @Override
    @Transactional
    public CreateProductView create(CreateProductCommand command) {
        // I/O 전에 순수 검증부터 — 잘못된 입력이면 저장 시도조차 하지 않는다
        ProductTagPolicy.validate(command.tags());
        if (categoryRepository.findById(command.categoryId()).isEmpty()) {
            throw new CategoryNotFoundException("카테고리를 찾을 수 없습니다: " + command.categoryId());
        }

        Instant now = timeProvider.now();

        // 1단계: 상품 + 옵션 타입/값 저장 → 옵션값 id 확정
        Product product = Product.create(
                        command.sellerId(),
                        command.categoryId(),
                        command.name(),
                        command.description(),
                        command.basePrice(),
                        command.shippingPolicyId(),
                        command.additionalShippingFee(),
                        ProductOptionModel.COMBINATION,
                        now)
                .toBuilder()
                .tags(command.tags())
                .optionTypes(ProductOptionCommandMapper.toOptionTypeModels(command.optionTypes()))
                .build();
        Product saved = productRepository.save(product);

        // 2단계: 확정된 옵션값 id로 조합을 생성하고 각각 저장 (조합은 별도 애그리거트)
        List<ProductOptionCombination> combinations = CombinationGenerator.generate(
                saved.id(),
                saved.basePrice(),
                saved.optionTypes(),
                List.of(),
                List.of(),
                command.defaultStock(),
                now);
        combinations.forEach(combinationRepository::save);

        return new CreateProductView(saved.id(), saved.status().name());
    }
}
