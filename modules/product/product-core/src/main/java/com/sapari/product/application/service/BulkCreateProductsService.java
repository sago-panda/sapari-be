package com.sapari.product.application.service;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.BulkCreateProductsCommand;
import com.sapari.product.command.BulkProductItemCommand;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.product.domain.exception.ProductDomainException;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.model.product.ProductTagPolicy;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.port.BulkCreateProductsUseCase;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.view.BulkCreateProductsView;
import com.sapari.product.view.BulkCreatedProductView;
import com.sapari.product.view.BulkProductErrorView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 상품 대량 등록 유스케이스. 2단계로 처리한다.
 *
 * <ol>
 *   <li><b>사전 검증</b> — 모든 상품에 대해 태그 규칙, 옵션 룰 구조, 조합 생성 규칙(조합 수·가격 범위·전량 제외), 카테고리 존재를 확인하고 오류를
 *       <b>전부</b> 모은다. 하나라도 있으면 아무것도 저장하지 않고 {@code REJECTED}로 돌려준다. 파일의 상품 코드는 저장되지 않아 부분 저장 후 재업로드하면
 *       중복 등록되므로, 검증 단계에서 파일 전체를 거절하는 것이 기본이다.
 *   <li><b>저장</b> — 상품마다 {@link CreateProductUseCase}를 호출해 트랜잭션을 나눈다(한 트랜잭션에 최대 100상품 × 500조합을 담지 않기 위해).
 *       검증 이후의 경합(예: 카테고리 삭제)으로 한 상품이 실패하면 거기서 멈추고 {@code STOPPED}와 함께 이미 저장된 상품을 돌려준다.
 * </ol>
 *
 * <p>이 클래스는 트랜잭션을 열지 않는다 — 트랜잭션은 상품 단위로 {@link CreateProductUseCase} 구현이 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkCreateProductsService implements BulkCreateProductsUseCase {

    private final CreateProductUseCase createProductUseCase;
    private final CategoryRepository categoryRepository;
    private final TimeProvider timeProvider;

    /**
     * 상품들을 사전 검증한 뒤 일괄 등록한다.
     *
     * @throws InvalidBulkUploadException 상품이 없거나 한도를 넘거나 상품 코드가 비었거나 중복된 경우(요청 자체가 성립하지 않음)
     */
    @Override
    public BulkCreateProductsView create(BulkCreateProductsCommand command) {
        List<BulkProductItemCommand> items = command.items() == null ? List.of() : command.items();
        guardRequest(items);

        List<BulkProductErrorView> errors = validateAll(items);
        if (!errors.isEmpty()) {
            return new BulkCreateProductsView(BulkCreateProductsView.REJECTED, List.of(), errors);
        }
        return registerAll(items);
    }

    /**
     * 요청이 대량 등록으로 성립하는지 확인한다 — 상품 1~{@link BulkCreateProductsCommand#MAX_ITEMS}개, 상품 코드는 비어 있지 않고 유일.
     */
    private void guardRequest(List<BulkProductItemCommand> items) {
        if (items.isEmpty()) {
            throw new InvalidBulkUploadException("등록할 상품이 없습니다.");
        }
        if (items.size() > BulkCreateProductsCommand.MAX_ITEMS) {
            throw new InvalidBulkUploadException(
                    "상품은 한 번에 " + BulkCreateProductsCommand.MAX_ITEMS + "개까지 등록할 수 있습니다: " + items.size());
        }
        Set<String> codes = new HashSet<>();
        for (BulkProductItemCommand item : items) {
            if (item.productCode() == null || item.productCode().isBlank()) {
                throw new InvalidBulkUploadException("상품 코드가 비어 있습니다.");
            }
            // 결과·오류를 파일 행에 되돌려 매핑하는 키라 유일해야 한다
            if (!codes.add(item.productCode())) {
                throw new InvalidBulkUploadException("상품 코드가 중복됩니다: " + item.productCode());
            }
        }
    }

    /**
     * 모든 상품을 저장 없이 검증해 오류를 전부 모은다(첫 오류에서 멈추지 않음 — 판매자가 한 번에 고칠 수 있도록).
     */
    private List<BulkProductErrorView> validateAll(List<BulkProductItemCommand> items) {
        Instant now = timeProvider.now();
        // 같은 카테고리를 쓰는 상품이 많으므로 카테고리 존재 여부는 id당 한 번만 조회한다
        Map<Long, Boolean> categoryExists = new HashMap<>();
        List<BulkProductErrorView> errors = new ArrayList<>();
        for (BulkProductItemCommand item : items) {
            CreateProductCommand product = item.product();
            try {
                validateProduct(product, now);
                boolean exists = categoryExists.computeIfAbsent(
                        product.categoryId(), id -> categoryRepository.findById(id).isPresent());
                if (!exists) {
                    throw new CategoryNotFoundException("카테고리를 찾을 수 없습니다: " + product.categoryId());
                }
            } catch (ProductDomainException e) {
                // 도메인 규칙 위반을 상품별 오류로 번역해 모은다 — 요청 전체를 실패시키지 않는다
                errors.add(toError(item.productCode(), e));
            }
        }
        return errors;
    }

    /**
     * 상품 한 건을 저장 없이 검증한다. 등록 유스케이스가 저장 전·후에 거는 규칙을 모두 미리 확인한다.
     *
     * <p>조합 생성 규칙(조합 수·가격 범위·전량 제외)은 저장 후 부여되는 옵션값 id가 필요하므로, 임시 id를 부여한 옵션으로 조합 생성기를 그대로 돌려 확인한다.
     * 결과는 버린다.
     */
    private void validateProduct(CreateProductCommand product, Instant now) {
        ProductTagPolicy.validate(product.tags());
        ProductOptionRuleAssembler.validate(
                product.optionTypes(), product.surchargeRules(), product.exclusionRules(), product.combinations());

        List<ProductOptionTypeModel> typesWithIds =
                withTemporaryIds(ProductOptionCommandMapper.toOptionTypeModels(product.optionTypes()));
        Map<String, UUID> refToId = ProductOptionRuleAssembler.resolveRefs(product.optionTypes(), typesWithIds);
        CombinationGenerator.generate(
                UUID.randomUUID(),
                product.basePrice(),
                typesWithIds,
                ProductOptionRuleAssembler.toSurchargeRules(product.surchargeRules(), refToId),
                ProductOptionRuleAssembler.toExclusionRules(product.exclusionRules(), refToId),
                product.defaultStock(),
                now);
    }

    /**
     * 검증을 통과한 상품들을 파일 순서대로 저장한다. 한 상품이 실패하면 멈추고 그때까지의 결과를 돌려준다.
     */
    private BulkCreateProductsView registerAll(List<BulkProductItemCommand> items) {
        List<BulkCreatedProductView> created = new ArrayList<>();
        for (BulkProductItemCommand item : items) {
            try {
                // 상품마다 별도 트랜잭션(프록시 호출) — 앞서 저장된 상품은 뒤 상품의 실패로 롤백되지 않는다
                UUID productId = createProductUseCase.create(item.product()).productId();
                created.add(new BulkCreatedProductView(item.productCode(), productId));
            } catch (ProductDomainException e) {
                // 사전 검증 뒤의 경합으로만 도달한다. 이미 저장된 상품을 판매자가 알 수 있도록 결과로 번역하고 나머지는 시도하지 않는다
                log.warn("[{}] 대량 등록 중단. productCode={}, created={}, reason={}",
                        e.getErrorCode().getCode(), item.productCode(), created.size(), e.getMessage());
                return new BulkCreateProductsView(
                        BulkCreateProductsView.STOPPED, created, List.of(toError(item.productCode(), e)));
            } catch (RuntimeException e) {
                // 인프라 장애 등은 결과로 감추지 않고 그대로 던진다. 다만 앞선 상품은 이미 커밋됐으므로(재업로드 시 중복 등록 위험)
                // 무엇이 저장됐는지 추적할 수 있게 저장분을 남긴 뒤 던진다
                log.error("대량 등록 중 예외로 중단 — 이미 저장된 상품이 있다. failedProductCode={}, created={}",
                        item.productCode(), created, e);
                throw e;
            }
        }
        return new BulkCreateProductsView(BulkCreateProductsView.COMPLETED, created, List.of());
    }

    /**
     * 옵션 타입·값에 임시 id를 부여한다(조합 생성 규칙 사전 확인용, 저장하지 않음).
     */
    private static List<ProductOptionTypeModel> withTemporaryIds(List<ProductOptionTypeModel> types) {
        return types.stream()
                .map(type -> new ProductOptionTypeModel(
                        UUID.randomUUID(),
                        type.attributeGroupId(),
                        type.name(),
                        type.sortOrder(),
                        type.values()
                                .stream()
                                .map(value -> new ProductOptionValueModel(
                                        UUID.randomUUID(),
                                        value.attributePresetId(),
                                        value.value(),
                                        value.metadata(),
                                        value.priceDelta(),
                                        value.sortOrder()))
                                .toList()))
                .toList();
    }

    /**
     * 도메인 예외를 상품별 오류로 번역한다. 메시지는 판매자가 올린 값만 담은 위반 내용(디버그 메시지)이다.
     *
     * <p>{@code BusinessException}의 디버그 메시지는 원래 로그 전용이다({@code GlobalExceptionHandler}는 카탈로그 메시지만 응답). 대량 등록 결과는
     * 판매자가 파일을 고칠 수 있어야 해서 예외적으로 이를 응답에 담는다 — 여기에 도달하는 예외(태그·옵션 룰·조합 생성·카테고리)의 메시지에는
     * 판매자 본인 입력값 외의 내부 식별자를 넣지 않는다.
     */
    private static BulkProductErrorView toError(String productCode, ProductDomainException e) {
        return new BulkProductErrorView(productCode, e.getErrorCode().getCode(), e.getMessage());
    }
}
