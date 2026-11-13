package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.BulkCreateProductsCommand;
import com.sapari.product.command.BulkProductItemCommand;
import com.sapari.product.command.CombinationOverrideCommand;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.command.ExclusionRuleCommand;
import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.command.SurchargeRuleCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.CombinationNotFoundException;
import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.InvalidProductStateException;
import com.sapari.product.domain.exception.InvalidProductTagException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductConcurrentModificationException;
import com.sapari.product.domain.exception.ProductDomainException;
import com.sapari.product.domain.exception.ProductErrorCode;
import com.sapari.product.domain.exception.TooManyCombinationsException;
import com.sapari.product.domain.model.category.Category;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.view.BulkCreateProductsView;
import com.sapari.product.view.BulkCreatedProductView;
import com.sapari.product.view.BulkProductErrorView;
import com.sapari.product.view.CreateProductView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BulkCreateProductsService")
class BulkCreateProductsServiceTest {

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Long CATEGORY_ID = 1001L;
    private static final Long MISSING_CATEGORY_ID = 9999L;
    /** 응답으로 새면 안 되는 내부 식별자(허용 목록 밖 예외의 디버그 메시지에 담는다). */
    private static final UUID INTERNAL_ID = UUID.fromString("00000000-0000-0000-0000-00000000dead");
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Mock
    private CreateProductUseCase createProductUseCase;

    @Mock
    private CategoryRepository categoryRepository;

    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));

    private BulkCreateProductsService service;

    @BeforeEach
    void setUp() {
        service = new BulkCreateProductsService(createProductUseCase, categoryRepository, timeProvider);
    }

    private static Category aCategory() {
        return Category.create(null, "1001", "의류", (short) 1, 0, true);
    }

    private static ProductOptionValueCommand value(String value, int priceDelta, short sortOrder, String ref) {
        return new ProductOptionValueCommand(value, null, null, priceDelta, sortOrder, ref);
    }

    /** 옵션·룰 없는 단일 상품 커맨드. */
    private static CreateProductCommand simpleProduct(Long categoryId, List<String> tags) {
        return new CreateProductCommand(
                SELLER_ID, categoryId, "상품", "설명", 10_000, null, 0, tags, List.of(), 10, null, null, null);
    }

    /** 색상(빨강·파랑) × 사이즈(S·M) 옵션에 룰·오버라이드를 얹은 커맨드. */
    private static CreateProductCommand optionProduct(
            List<SurchargeRuleCommand> surcharges,
            List<ExclusionRuleCommand> exclusions,
            List<CombinationOverrideCommand> overrides) {
        ProductOptionTypeCommand color = new ProductOptionTypeCommand("색상", null, (short) 1, List.of(
                value("빨강", 0, (short) 1, "c-red"), value("파랑", 0, (short) 2, "c-blue")));
        ProductOptionTypeCommand size = new ProductOptionTypeCommand("사이즈", null, (short) 2, List.of(
                value("S", 0, (short) 1, "s-s"), value("M", 1_000, (short) 2, "s-m")));
        return new CreateProductCommand(
                SELLER_ID, CATEGORY_ID, "티셔츠", "설명", 19_900, null, 0, List.of("여름"),
                List.of(color, size), 100, surcharges, exclusions, overrides);
    }

    /** 옵션 타입 하나에 값 {@code valueCount}개인 커맨드(조합 수 = valueCount^typeCount). */
    private static CreateProductCommand bigOptionProduct(int typeCount, int valueCount) {
        List<ProductOptionTypeCommand> types = IntStream.range(0, typeCount)
                .mapToObj(t -> new ProductOptionTypeCommand("t" + t, null, (short) (t + 1),
                        IntStream.range(0, valueCount)
                                .mapToObj(v -> value("v" + v, 0, (short) (v + 1), null))
                                .toList()))
                .toList();
        return new CreateProductCommand(
                SELLER_ID, CATEGORY_ID, "대형", "설명", 1_000, null, 0, List.of(), types, 1, null, null, null);
    }

    private static BulkProductItemCommand item(String code, CreateProductCommand product) {
        return new BulkProductItemCommand(code, product);
    }

    private static BulkCreateProductsCommand bulk(BulkProductItemCommand... items) {
        return new BulkCreateProductsCommand(List.of(items));
    }

    @Nested
    @DisplayName("요청 성립 검사 (InvalidBulkUploadException, PRODUCT-012)")
    class GuardRequest {

        private void assertRejectedWithoutIo(BulkCreateProductsCommand command) {
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOfSatisfying(InvalidBulkUploadException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_BULK_UPLOAD));
            then(categoryRepository).shouldHaveNoInteractions();
            then(createProductUseCase).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("상품이 없으면(null·빈 목록) 거절한다")
        void rejectsEmpty() {
            assertRejectedWithoutIo(new BulkCreateProductsCommand(null));
            assertRejectedWithoutIo(new BulkCreateProductsCommand(List.of()));
        }

        @Test
        @DisplayName("상품이 101개면 거절한다 (한도 100)")
        void rejectsOverLimit() {
            List<BulkProductItemCommand> items = IntStream.rangeClosed(1, BulkCreateProductsCommand.MAX_ITEMS + 1)
                    .mapToObj(i -> item("P-" + i, simpleProduct(CATEGORY_ID, List.of())))
                    .toList();

            assertRejectedWithoutIo(new BulkCreateProductsCommand(items));
        }

        @Test
        @DisplayName("상품 코드가 비었거나 중복되면 거절한다")
        void rejectsBlankOrDuplicateCode() {
            assertRejectedWithoutIo(bulk(item(" ", simpleProduct(CATEGORY_ID, List.of()))));
            assertRejectedWithoutIo(bulk(
                    item("P-1", simpleProduct(CATEGORY_ID, List.of())),
                    item("P-1", simpleProduct(CATEGORY_ID, List.of()))));
        }

        @Test
        @DisplayName("경계: 상품이 정확히 100개면 처리한다")
        void acceptsExactlyLimit() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(createProductUseCase.create(any())).willReturn(new CreateProductView(UUID.randomUUID(), "PENDING_REVIEW"));
            List<BulkProductItemCommand> items = IntStream.rangeClosed(1, BulkCreateProductsCommand.MAX_ITEMS)
                    .mapToObj(i -> item("P-" + i, simpleProduct(CATEGORY_ID, List.of())))
                    .toList();

            BulkCreateProductsView view = service.create(new BulkCreateProductsCommand(items));

            assertThat(view.status()).isEqualTo(BulkCreateProductsView.COMPLETED);
            assertThat(view.created()).hasSize(BulkCreateProductsCommand.MAX_ITEMS);
        }
    }

    @Nested
    @DisplayName("사전 검증 실패 — REJECTED, 아무것도 저장하지 않음")
    class Rejected {

        @Test
        @DisplayName("상품마다 다른 위반을 첫 오류에서 멈추지 않고 파일 순서대로 전부 모은다")
        void collectsEveryErrorInFileOrder() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(categoryRepository.findById(MISSING_CATEGORY_ID)).willReturn(Optional.empty());

            BulkCreateProductsView view = service.create(bulk(
                    item("P-OK", optionProduct(null, null, null)),
                    item("P-TAG", simpleProduct(CATEGORY_ID, List.of("여름!"))),
                    item("P-REF", optionProduct(List.of(new SurchargeRuleCommand(List.of("c-green"), 100)), null, null)),
                    item("P-MANY", bigOptionProduct(3, 8)),
                    item("P-ALLEX", optionProduct(null, List.of(
                            new ExclusionRuleCommand(List.of("c-red"), null),
                            new ExclusionRuleCommand(List.of("c-blue"), null)), null)),
                    item("P-CAT", simpleProduct(MISSING_CATEGORY_ID, List.of()))));

            assertThat(view.status()).isEqualTo(BulkCreateProductsView.REJECTED);
            assertThat(view.created()).isEmpty();
            assertThat(view.errors())
                    .extracting(BulkProductErrorView::productCode, BulkProductErrorView::code)
                    .containsExactly(
                            tuple("P-TAG", "PRODUCT-007"),
                            tuple("P-REF", "PRODUCT-004"),
                            tuple("P-MANY", "PRODUCT-009"),
                            tuple("P-ALLEX", "PRODUCT-004"),
                            tuple("P-CAT", "PRODUCT-005"));
            then(createProductUseCase).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("오류 메시지는 카탈로그 문구가 아니라 판매자가 고칠 수 있는 위반 내용이다")
        void messageDescribesViolation() {
            BulkCreateProductsView view = service.create(bulk(
                    item("P-REF", optionProduct(List.of(new SurchargeRuleCommand(List.of("c-green"), 100)), null, null))));

            assertThat(view.errors()).singleElement()
                    .satisfies(e -> assertThat(e.message()).contains("c-green"));
        }

        @Test
        @DisplayName("조합 가격이 int 범위를 넘는 상품도 저장 전에 거절한다 (조합 생성 규칙 사전 확인)")
        void rejectsPriceOverflowBeforeSaving() {
            BulkCreateProductsView view = service.create(bulk(item("P-OVER", optionProduct(List.of(
                    new SurchargeRuleCommand(List.of("c-red"), Integer.MAX_VALUE)), null, null))));

            assertThat(view.status()).isEqualTo(BulkCreateProductsView.REJECTED);
            assertThat(view.errors()).singleElement()
                    .satisfies(e -> assertThat(e.code()).isEqualTo("PRODUCT-004"));
            then(createProductUseCase).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("카테고리 존재 여부는 카테고리 id당 한 번만 조회한다")
        void looksUpEachCategoryOnce() {
            given(categoryRepository.findById(MISSING_CATEGORY_ID)).willReturn(Optional.empty());

            service.create(bulk(
                    item("P-1", simpleProduct(MISSING_CATEGORY_ID, List.of())),
                    item("P-2", simpleProduct(MISSING_CATEGORY_ID, List.of())),
                    item("P-3", simpleProduct(MISSING_CATEGORY_ID, List.of()))));

            then(categoryRepository).should(times(1)).findById(MISSING_CATEGORY_ID);
        }
    }

    @Nested
    @DisplayName("저장")
    class Register {

        @Test
        @DisplayName("검증을 통과하면 파일 순서대로 상품마다 등록 유스케이스를 호출하고 COMPLETED와 저장된 id를 돌려준다")
        void registersEachInOrder() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            CreateProductCommand first = optionProduct(
                    List.of(new SurchargeRuleCommand(List.of("c-red", "s-m"), 500)),
                    List.of(new ExclusionRuleCommand(List.of("c-blue", "s-s"), "미생산")),
                    List.of(new CombinationOverrideCommand(List.of("c-blue", "s-m"), 9_900, null, 3, "SKU-1")));
            CreateProductCommand second = simpleProduct(CATEGORY_ID, List.of("가을"));
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(createProductUseCase.create(first)).willReturn(new CreateProductView(id1, "PENDING_REVIEW"));
            given(createProductUseCase.create(second)).willReturn(new CreateProductView(id2, "PENDING_REVIEW"));

            BulkCreateProductsView view = service.create(bulk(item("P-001", first), item("P-002", second)));

            assertThat(view.status()).isEqualTo(BulkCreateProductsView.COMPLETED);
            assertThat(view.errors()).isEmpty();
            assertThat(view.created()).containsExactly(
                    new BulkCreatedProductView("P-001", id1),
                    new BulkCreatedProductView("P-002", id2));
            InOrder inOrder = inOrder(createProductUseCase);
            inOrder.verify(createProductUseCase).create(first);
            inOrder.verify(createProductUseCase).create(second);
        }

        @Test
        @DisplayName("검증 뒤 저장 중 도메인 오류가 나면 STOPPED — 앞선 저장분·실패 1건을 돌려주고 나머지는 시도하지 않는다")
        void stopsAtFirstRegistrationFailure() {
            UUID id1 = UUID.randomUUID();
            CreateProductCommand first = simpleProduct(CATEGORY_ID, List.of("a"));
            CreateProductCommand second = simpleProduct(CATEGORY_ID, List.of("b"));
            CreateProductCommand third = simpleProduct(CATEGORY_ID, List.of("c"));
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(createProductUseCase.create(first)).willReturn(new CreateProductView(id1, "PENDING_REVIEW"));
            given(createProductUseCase.create(second))
                    .willThrow(new CategoryNotFoundException("카테고리를 찾을 수 없습니다: " + CATEGORY_ID));

            BulkCreateProductsView view = service.create(bulk(
                    item("P-1", first), item("P-2", second), item("P-3", third)));

            assertThat(view.status()).isEqualTo(BulkCreateProductsView.STOPPED);
            assertThat(view.created()).containsExactly(new BulkCreatedProductView("P-1", id1));
            assertThat(view.errors()).containsExactly(
                    new BulkProductErrorView("P-2", "PRODUCT-005", "카테고리를 찾을 수 없습니다: " + CATEGORY_ID));
            then(createProductUseCase).should(never()).create(third);
        }

        @Test
        @DisplayName("도메인 오류가 아닌 예외(인프라 장애 등)는 결과로 감추지 않고 그대로 전파한다")
        void propagatesNonDomainFailures() {
            CreateProductCommand first = simpleProduct(CATEGORY_ID, List.of());
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            IllegalStateException infraFailure = new IllegalStateException("db down");
            given(createProductUseCase.create(first)).willThrow(infraFailure);

            List<BulkProductItemCommand> items = new ArrayList<>(List.of(item("P-1", first)));

            assertThatThrownBy(() -> service.create(new BulkCreateProductsCommand(items)))
                    .isSameAs(infraFailure);
        }
    }

    @Nested
    @DisplayName("오류 메시지 노출 — 허용 목록 밖의 코드는 카탈로그 문구")
    class MessageExposure {

        /**
         * 저장 중 해당 예외가 나도록 해 STOPPED 결과의 오류 1건을 돌려준다.
         */
        private BulkProductErrorView stoppedErrorFor(ProductDomainException failure) {
            CreateProductCommand product = simpleProduct(CATEGORY_ID, List.of());
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(createProductUseCase.create(product)).willThrow(failure);

            BulkCreateProductsView view = service.create(bulk(item("P-1", product)));

            assertThat(view.status()).isEqualTo(BulkCreateProductsView.STOPPED);
            return view.errors().get(0);
        }

        /**
         * 판매자 입력값만 담는 코드(태그·옵션·조합 수·카테고리)는 위반 내용(디버그 메시지)을 그대로 담는다.
         *
         * @param failure 허용 목록 코드의 도메인 예외
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("violationDetailFailures")
        @DisplayName("허용 목록 코드(004·005·007·009)는 위반 내용을 그대로 담는다")
        void exposesViolationDetail(ProductDomainException failure) {
            BulkProductErrorView error = stoppedErrorFor(failure);

            assertThat(error.code()).isEqualTo(failure.getErrorCode().getCode());
            assertThat(error.message()).isEqualTo(failure.getMessage());
        }

        /**
         * 메시지 내용이 보장되지 않는 코드는 디버그 메시지(내부 식별자 포함 가능)를 버리고 카탈로그 문구를 담는다.
         *
         * @param failure 허용 목록 밖 코드의 도메인 예외(디버그 메시지에 내부 식별자를 담음)
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("catalogOnlyFailures")
        @DisplayName("허용 목록 밖 코드는 디버그 메시지 대신 카탈로그 문구를 담는다 — 내부 식별자가 응답으로 새지 않는다")
        void hidesDebugMessageOutsideWhitelist(ProductDomainException failure) {
            BulkProductErrorView error = stoppedErrorFor(failure);

            assertThat(error.code()).isEqualTo(failure.getErrorCode().getCode());
            assertThat(error.message())
                    .isEqualTo(failure.getErrorCode().getMessage())
                    .doesNotContain(INTERNAL_ID.toString());
        }

        /**
         * 허용 목록 코드의 대표 예외들.
         */
        static Stream<ProductDomainException> violationDetailFailures() {
            return Stream.of(
                    new InvalidProductOptionException("옵션 타입명이 중복됩니다: 색상"),
                    new CategoryNotFoundException("카테고리를 찾을 수 없습니다: 1001"),
                    new InvalidProductTagException("태그 형식이 올바르지 않습니다: #여름"),
                    new TooManyCombinationsException("조합 수 600이 한도 500을 넘습니다"));
        }

        /**
         * 허용 목록 밖 코드의 예외들 — 디버그 메시지에 내부 식별자를 담는다.
         */
        static Stream<ProductDomainException> catalogOnlyFailures() {
            return Stream.of(
                    new InvalidProductStateException("내부 상품 상태 전이 실패: " + INTERNAL_ID),
                    new ProductConcurrentModificationException("낙관적 락 충돌: " + INTERNAL_ID),
                    new ProductAccessDeniedException("소유자 불일치: " + INTERNAL_ID),
                    new CombinationNotFoundException("조합 없음: " + INTERNAL_ID));
        }
    }
}
