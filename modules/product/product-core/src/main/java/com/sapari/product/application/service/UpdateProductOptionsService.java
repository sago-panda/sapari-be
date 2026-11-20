package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CombinationOverrideCommand;
import com.sapari.product.command.UpdateProductOptionsCommand;
import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.SurchargeRule;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.port.UpdateProductOptionsUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 옵션 수정 유스케이스. 옵션 타입/값을 교체하고 옵션 조합을 재생성한다.
 *
 * <p>기존 조합은 주문이 id로 직접 참조하므로 물리 삭제하지 않고 단종({@code isAvailable=false}) 처리한 뒤, 새 옵션값으로 새 조합을 생성한다.
 * 새 옵션값은 새 id를 받으므로 조합 키가 달라 기존 조합과 충돌하지 않는다. 추가금/제외 룰과 조합별 오버라이드도 새 옵션값 기준으로 통째로
 * 교체되며, 룰이 없으면 기존 config를 지운다(옛 옵션값 id를 가리키는 룰이 남지 않도록).
 *
 * <p>옵션 교체는 재검수 대상이 아니다(가격·재고·구조 변경은 즉시 반영, PRD §18.3). 조합이 바뀌므로 상품 캐시 컬럼(min_price·has_stock)을
 * 트랜잭션 끝에서 다시 계산한다.
 */
@Service
@RequiredArgsConstructor
public class UpdateProductOptionsService implements UpdateProductOptionsUseCase {

    private final ProductRepository productRepository;
    private final ProductOptionCombinationRepository combinationRepository;
    private final TimeProvider timeProvider;

    /**
     * 상품 옵션을 교체하고 조합을 재생성한다. 존재·소유권·옵션 룰을 확인하고, 기존 조합 단종 → 루트 저장(version 검사) → 옵션 타입/값 교체 →
     * 룰·오버라이드를 반영한 새 조합 생성·저장 → config 교체 순으로 진행한다.
     *
     * @throws ProductNotFoundException      상품이 없거나 삭제된 경우
     * @throws ProductAccessDeniedException  요청 판매자가 소유자가 아닌 경우
     * @throws InvalidProductOptionException 옵션 구성·룰·오버라이드가 올바르지 않거나, 기존 조합이 쓰던 SKU를 재사용한 경우
     */
    @Override
    @Transactional
    public void update(UpdateProductOptionsCommand command) {
        Product product = productRepository.findActiveById(command.productId())
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: " + command.productId()));

        if (!product.sellerId().equals(command.sellerId())) {
            throw new ProductAccessDeniedException(
                    "상품 소유자가 아닙니다: product=" + command.productId() + ", seller=" + command.sellerId());
        }

        // 쓰기 전에 입력부터 거절 — 잘못된 룰이면 기존 조합을 단종시키지 않는다
        ProductOptionRuleAssembler.validate(
                command.optionTypes(), command.surchargeRules(), command.exclusionRules(), command.combinations());
        rejectReusedSkus(product.id(), command.combinations());

        Instant now = timeProvider.now();

        // 기존 조합 일괄 단종 — 주문 참조 보존을 위해 삭제 대신 isAvailable=false로 은퇴 (건당 save N+1 대신 벌크 UPDATE)
        combinationRepository.discontinueAllByProductId(product.id(), now);

        // 루트 갱신 — 클라이언트가 본 version으로 덮어써야 저장 시 stale 비교가 동작한다(§13). save는 옵션 트리를 건드리지 않는다
        productRepository.save(product.toBuilder()
                .updatedAt(now)
                .version(command.expectedVersion())
                .build());
        // 새 옵션 타입/값으로 교체 → 옵션값 id 확정
        Product saved = productRepository.replaceOptionTypes(
                product.id(), ProductOptionCommandMapper.toOptionTypeModels(command.optionTypes()));

        // 요청의 ref를 확정된 새 옵션값 id로 해석해 룰을 만든다
        Map<String, UUID> refToId = ProductOptionRuleAssembler.resolveRefs(command.optionTypes(), saved.optionTypes());
        List<SurchargeRule> surchargeRules =
                ProductOptionRuleAssembler.toSurchargeRules(command.surchargeRules(), refToId);
        List<ExclusionRule> exclusionRules =
                ProductOptionRuleAssembler.toExclusionRules(command.exclusionRules(), refToId);

        // 룰을 반영해 조합 재생성 → 오버라이드 반영 → 일괄 저장 (신규 조합이라 건당 INSERT 왕복(N+1) 방지)
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
        combinationRepository.saveAll(combinations);
        // 옛 조합 단종·새 조합 생성 결과로 최저가·재고유무 캐시를 다시 계산
        productRepository.refreshStockPriceCache(saved.id(), now);

        // config는 룰이 없어도 항상 교체(null이면 삭제) — 기존 룰은 방금 교체된 옛 옵션값 id를 가리키므로 남기면 안 된다
        productRepository.replaceOptionConfig(
                saved.id(), ProductOptionRuleAssembler.toConfig(surchargeRules, exclusionRules));
    }

    /**
     * 오버라이드 SKU가 이 상품의 기존 조합(단종 포함) SKU와 겹치면 거절한다.
     *
     * <p>DB 유니크 {@code (product_id, sku)}는 부분 인덱스가 아니라 단종 조합의 SKU도 점유한다. 단종 후 재생성하는 옵션 교체에서
     * 같은 SKU를 쓰면 저장 시 제약 위반(서버 오류)이 나므로, 쓰기 전에 입력 오류로 돌려준다. SKU 지정이 없으면 조회하지 않는다.
     */
    private void rejectReusedSkus(UUID productId, List<CombinationOverrideCommand> overrides) {
        Set<String> requested = overrides == null ? Set.of() : overrides.stream()
                .map(CombinationOverrideCommand::sku)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (requested.isEmpty()) {
            return;
        }
        combinationRepository.findByProductId(productId)
                .stream()
                .map(ProductOptionCombination::sku)
                .filter(Objects::nonNull)
                .map(Sku::value)
                .filter(requested::contains)
                .findFirst()
                .ifPresent(sku -> {
                    throw new InvalidProductOptionException("기존 조합이 사용 중인 SKU는 재사용할 수 없습니다: " + sku);
                });
    }
}
