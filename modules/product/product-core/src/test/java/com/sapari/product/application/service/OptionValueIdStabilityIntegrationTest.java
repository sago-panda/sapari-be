package com.sapari.product.application.service;

import static com.sapari.product.support.ProductFixtures.SELLER_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.ChangeSaleStatusCommand;
import com.sapari.product.command.CombinationUpdateCommand;
import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.command.UpdateCombinationPriceStockCommand;
import com.sapari.product.command.UpdateProductInfoCommand;
import com.sapari.product.domain.model.category.Category;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import com.sapari.product.support.ProductFixtures;
import com.sapari.product.view.CombinationView;
import com.sapari.product.view.OptionValueView;
import com.sapari.product.view.ProductDetailView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 옵션을 바꾸지 않는 상품 저장(정보 수정·판매상태 변경)이 옵션값 id를 보존해, 조합 참조가 끊기지 않는지 실제 서비스·Postgres로 검증한다.
 *
 * <p>회귀 대상: 기존 상품 save가 옵션 트리를 삭제 후 재삽입해 옵션값 id를 재발급하던 결함. 그때는 정보 수정·판매상태 변경 뒤 ① 판매자가 단종한 조합을
 * 재개하면 "옵션 교체로 은퇴한 조합"으로 오판돼 PRODUCT-002로 거절됐고, ② 구매자 상세의 조합 {@code optionValueIds}가 옵션 트리에 없는 옛 id를
 * 가리켰다.
 *
 * <p>영속 테스트 컨텍스트는 서비스 빈을 스캔하지 않으므로, 실제 어댑터를 주입해 서비스를 직접 조립한다.
 */
@DisplayName("옵션값 id 안정성 — 옵션을 바꾸지 않는 저장 뒤에도 조합 참조가 유지된다 (통합)")
class OptionValueIdStabilityIntegrationTest extends AbstractRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final int BASE_PRICE = 10_000;
    private static final int DEFAULT_STOCK = 10;

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductOptionCombinationRepository combinationRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    @PersistenceContext
    private EntityManager em;

    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));

    private UpdateProductInfoService updateInfoService;
    private ChangeProductSaleStatusService changeSaleStatusService;
    private UpdateCombinationPriceStockService updateCombinationService;
    private GetProductDetailService getDetailService;

    private Long categoryId;
    private UUID productId;
    private UUID discontinuedId;

    /**
     * 서비스를 조립하고, 판매 중인 옵션 상품(옵션 타입 2개)과 그 조합을 만든 뒤 조합 하나를 판매자가 단종해 둔다.
     */
    @BeforeEach
    void setUp() {
        updateInfoService = new UpdateProductInfoService(productRepository, categoryRepository, timeProvider, html -> html);
        changeSaleStatusService = new ChangeProductSaleStatusService(productRepository, timeProvider);
        updateCombinationService = new UpdateCombinationPriceStockService(productRepository, combinationRepository, timeProvider);
        getDetailService = new GetProductDetailService(productRepository, combinationRepository);

        categoryId = categoryRepository.save(Category.create(null, "7001", "의류", (short) 1, 0, true))
                .id();
        Product saved = productRepository.save(Product.create(
                        SELLER_A, categoryId, "옵션 상품", "설명", BASE_PRICE, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW)
                .toBuilder()
                .optionTypes(ProductFixtures.optionTypes(2))
                .build());
        productId = saved.id();
        List<ProductOptionCombination> combinations = combinationRepository.saveAll(CombinationGenerator.generate(
                productId, BASE_PRICE, saved.optionTypes(), List.of(), List.of(), DEFAULT_STOCK, NOW));
        discontinuedId = combinations.get(0)
                .id();
        flushAndClear();

        setAvailability(false);
    }

    @Test
    @DisplayName("정보 수정 뒤에도 판매자가 단종한 조합을 다시 판매할 수 있다")
    void reactivates_after_info_update() {
        Product product = productRepository.findActiveById(productId)
                .orElseThrow();
        updateInfoService.update(new UpdateProductInfoCommand(productId, SELLER_A, categoryId, "수정된 상품명",
                "수정된 설명", null, 0, List.of("태그"), product.version()));
        flushAndClear();

        setAvailability(true);

        assertThat(combinationRepository.findById(discontinuedId)
                .orElseThrow()
                .isAvailable()).isTrue();
    }

    @Test
    @DisplayName("판매상태 변경 뒤에도 단종 조합을 재개할 수 있고, 구매자 상세의 조합은 현재 옵션 트리의 옵션값만 가리킨다")
    void reactivates_and_detail_stays_consistent_after_status_change() {
        changeStatus("SUSPENDED");
        changeStatus("ON_SALE");

        setAvailability(true);

        ProductDetailView detail = getDetailService.get(new GetProductDetailCommand(productId));
        Set<UUID> treeValueIds = detail.optionTypes()
                .stream()
                .flatMap(type -> type.values()
                        .stream())
                .map(OptionValueView::optionValueId)
                .collect(Collectors.toSet());
        assertThat(detail.combinations()).isNotEmpty()
                .allSatisfy(combination -> assertThat(treeValueIds).containsAll(combination.optionValueIds()));
        assertThat(detail.combinations()).filteredOn(c -> c.combinationId()
                        .equals(discontinuedId))
                .singleElement()
                .extracting(CombinationView::isAvailable)
                .isEqualTo(true);
    }

    /**
     * 현재 상품 version으로 판매상태를 바꾸고 영속성 컨텍스트를 비운다.
     */
    private void changeStatus(String target) {
        Long version = productRepository.findActiveById(productId)
                .orElseThrow()
                .version();
        changeSaleStatusService.change(new ChangeSaleStatusCommand(productId, SELLER_A, target, version));
        flushAndClear();
    }

    /**
     * 단종 대상으로 고른 조합의 판매여부를 현재 조합 version으로 바꾸고 영속성 컨텍스트를 비운다.
     */
    private void setAvailability(boolean available) {
        Long version = combinationRepository.findById(discontinuedId)
                .orElseThrow()
                .version();
        updateCombinationService.update(new UpdateCombinationPriceStockCommand(productId, SELLER_A, List.of(
                new CombinationUpdateCommand(discontinuedId, null, null, null, available, version))));
        flushAndClear();
    }

    /**
     * 쓰기를 DB에 반영하고 1차 캐시를 비워, 다음 조회가 DB에서 다시 읽게 한다.
     */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
