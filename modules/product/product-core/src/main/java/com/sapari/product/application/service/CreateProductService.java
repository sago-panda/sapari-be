package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.application.port.HtmlSanitizer;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.InvalidProductTagException;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.SurchargeRule;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductTagPolicy;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.view.CreateProductView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 등록 유스케이스. 상품(옵션 타입/값 포함)을 먼저 저장해 옵션값 id를 확정한 뒤, 그 id로 옵션 조합을 생성·저장한다.
 *
 * <p>Product와 옵션 조합은 별도 애그리거트라 한 트랜잭션 안에서 2단계로 저장한다. 추가금/제외 룰은 확정된 옵션값 id로 해석해 조합 생성에
 * 반영하고 config에 기록하며, 조합별 오버라이드는 생성된 조합에 덮어쓴다. 이미지는 후속 증분에서 추가한다.
 */
@Service
@RequiredArgsConstructor
public class CreateProductService implements CreateProductUseCase {

    private final ProductRepository productRepository;
    private final ProductOptionCombinationRepository combinationRepository;
    private final CategoryRepository categoryRepository;
    private final TimeProvider timeProvider;
    private final HtmlSanitizer htmlSanitizer;

    /**
     * 상품을 등록한다. 태그·옵션 룰·카테고리를 검증하고, 상품을 검수 대기 상태로 저장한 뒤 룰·오버라이드를 반영한 옵션 조합을 자동 생성·저장한다.
     *
     * @throws InvalidProductTagException    태그 규칙 위반(개수·길이·특수문자)인 경우
     * @throws InvalidProductOptionException 옵션 구성·룰·오버라이드가 올바르지 않은 경우
     * @throws CategoryNotFoundException     존재하지 않는 카테고리인 경우
     */
    @Override
    @Transactional
    public CreateProductView create(CreateProductCommand command) {
        // I/O 전에 순수 검증부터 — 잘못된 입력이면 저장 시도조차 하지 않는다
        ProductTagPolicy.validate(command.tags());
        ProductOptionRuleAssembler.validate(
                command.optionTypes(), command.surchargeRules(), command.exclusionRules(), command.combinations());
        if (categoryRepository.findById(command.categoryId()).isEmpty()) {
            throw new CategoryNotFoundException("카테고리를 찾을 수 없습니다: " + command.categoryId());
        }

        Instant now = timeProvider.now();

        // 1단계: 상품 + 옵션 타입/값 저장 → 옵션값 id 확정
        Product product = Product.create(
                        command.sellerId(),
                        command.categoryId(),
                        command.name(),
                        htmlSanitizer.sanitize(command.description()),
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

        // 2단계: 요청의 ref를 확정된 옵션값 id로 해석해 룰을 만든다
        Map<String, UUID> refToId = ProductOptionRuleAssembler.resolveRefs(command.optionTypes(), saved.optionTypes());
        List<SurchargeRule> surchargeRules =
                ProductOptionRuleAssembler.toSurchargeRules(command.surchargeRules(), refToId);
        List<ExclusionRule> exclusionRules =
                ProductOptionRuleAssembler.toExclusionRules(command.exclusionRules(), refToId);

        // 3단계: 룰을 반영해 조합을 생성하고 오버라이드를 덮어쓴 뒤 일괄 저장 (조합은 별도 애그리거트)
        List<ProductOptionCombination> generated = CombinationGenerator.generate(
                saved.id(),
                saved.basePrice(),
                saved.optionTypes(),
                surchargeRules,
                exclusionRules,
                command.defaultStock(),
                now);
        List<ProductOptionCombination> combinations =
                ProductOptionRuleAssembler.applyOverrides(generated, command.combinations(), refToId);
        // 조합은 한꺼번에 생성되므로 일괄 저장 — 건당 INSERT 왕복(N+1) 방지 (단일 상품도 기본 조합 1개가 있다)
        combinationRepository.saveAll(combinations);
        // 목록·검색이 쓰는 최저가·재고유무 캐시를 방금 저장한 조합으로 계산 (생성 시점 캐시 불일치 제거)
        productRepository.refreshStockPriceCache(saved.id(), now);

        // 룰이 있으면 config 행만 기록 — 룰은 방금 확정된 옵션값 id를 참조하므로 상품 저장 뒤에 따로 쓴다(기존 상품 save는 config를 쓰지 않음)
        ProductOptionConfigModel config = ProductOptionRuleAssembler.toConfig(surchargeRules, exclusionRules);
        if (config != null) {
            productRepository.replaceOptionConfig(saved.id(), config);
        }

        return new CreateProductView(saved.id(), saved.status().name());
    }
}
