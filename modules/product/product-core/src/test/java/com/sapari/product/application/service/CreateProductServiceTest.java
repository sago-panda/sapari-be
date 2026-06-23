package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.InvalidProductTagException;
import com.sapari.product.domain.model.category.Category;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.view.CreateProductView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateProductService")
class CreateProductServiceTest {

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SHIPPING_POLICY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Long CATEGORY_ID = 1001L;
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID S_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID M_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final int BASE_PRICE = 10_000;
    private static final int M_PRICE_DELTA = 1_000;
    private static final int DEFAULT_STOCK = 100;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionCombinationRepository combinationRepository;

    @Mock
    private CategoryRepository categoryRepository;

    // 실제 TimeProvider를 고정 Clock으로 — now()는 NOW를 결정적으로 반환(스텁 불필요)
    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));

    private CreateProductService service;

    @BeforeEach
    void setUp() {
        service = new CreateProductService(
                productRepository, combinationRepository, categoryRepository, timeProvider);
    }

    private static Category aCategory() {
        return Category.create(null, "1001", "의류", (short) 1, 0, true);
    }

    /** 사이즈(S:+0, M:+1000) 옵션 1개를 가진 등록 커맨드. */
    private static CreateProductCommand command() {
        return commandWith(List.of("여름"), sizeOptionCommand());
    }

    private static ProductOptionTypeCommand sizeOptionCommand() {
        ProductOptionValueCommand s = new ProductOptionValueCommand("S", null, null, 0, (short) 1);
        ProductOptionValueCommand m = new ProductOptionValueCommand("M", null, "{\"k\":1}", M_PRICE_DELTA, (short) 2);
        return new ProductOptionTypeCommand("사이즈", null, (short) 1, List.of(s, m));
    }

    private static CreateProductCommand commandWith(List<String> tags, ProductOptionTypeCommand... optionTypes) {
        return new CreateProductCommand(
                SELLER_ID, CATEGORY_ID, "베이직 티셔츠", "상세 설명", BASE_PRICE,
                SHIPPING_POLICY_ID, 2_500, tags, List.of(optionTypes), DEFAULT_STOCK);
    }

    /** productRepository.save가 돌려줄, 옵션값 id가 부여된 저장본. */
    private static Product savedProduct() {
        ProductOptionValueModel s = new ProductOptionValueModel(S_ID, null, "S", null, 0, (short) 1);
        ProductOptionValueModel m = new ProductOptionValueModel(M_ID, null, "M", "{\"k\":1}", M_PRICE_DELTA, (short) 2);
        ProductOptionTypeModel size = new ProductOptionTypeModel(UUID.randomUUID(), null, "사이즈", (short) 1, List.of(s, m));
        return Product.create(
                        SELLER_ID, CATEGORY_ID, "베이직 티셔츠", "상세 설명", BASE_PRICE,
                        SHIPPING_POLICY_ID, 2_500, ProductOptionModel.COMBINATION, NOW)
                .toBuilder()
                .id(PRODUCT_ID)
                .optionTypes(List.of(size))
                .build();
    }

    private Product captureSavedProduct() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        then(productRepository).should().save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<ProductOptionCombination> captureSavedCombinations() {
        ArgumentCaptor<List<ProductOptionCombination>> captor = ArgumentCaptor.forClass(List.class);
        then(combinationRepository).should().saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("성공 — 상품 저장")
    class SavesProduct {

        @BeforeEach
        void stubHappyPath() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(productRepository.save(any())).willReturn(savedProduct());
            given(combinationRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("커맨드의 모든 기본 필드를 그대로 담아 검수 대기(PENDING_REVIEW) 상태로 저장한다")
        void mapsAllBasicFields() {
            service.create(command());

            Product toSave = captureSavedProduct();
            assertThat(toSave.id()).isNull();
            assertThat(toSave.sellerId()).isEqualTo(SELLER_ID);
            assertThat(toSave.categoryId()).isEqualTo(CATEGORY_ID);
            assertThat(toSave.name()).isEqualTo("베이직 티셔츠");
            assertThat(toSave.description()).isEqualTo("상세 설명");
            assertThat(toSave.basePrice()).isEqualTo(BASE_PRICE);
            assertThat(toSave.shippingPolicyId()).isEqualTo(SHIPPING_POLICY_ID);
            assertThat(toSave.additionalShippingFee()).isEqualTo(2_500);
            assertThat(toSave.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(toSave.optionModel()).isEqualTo(ProductOptionModel.COMBINATION);
            assertThat(toSave.tags()).containsExactly("여름");
        }

        @Test
        @DisplayName("생성·수정 시각은 TimeProvider가 준 값으로 채운다 (Instant.now() 직접 사용 안 함)")
        void usesTimeProviderForTimestamps() {
            service.create(command());

            Product toSave = captureSavedProduct();
            assertThat(toSave.createdAt()).isEqualTo(NOW);
            assertThat(toSave.updatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("옵션 타입/값을 커맨드대로 매핑하고, 영속 전이라 id는 비운다")
        void mapsOptionTypesAndValues() {
            service.create(command());

            Product toSave = captureSavedProduct();
            assertThat(toSave.optionTypes()).singleElement().satisfies(type -> {
                assertThat(type.id()).isNull();
                assertThat(type.name()).isEqualTo("사이즈");
                assertThat(type.sortOrder()).isEqualTo((short) 1);
                assertThat(type.values()).extracting(ProductOptionValueModel::value).containsExactly("S", "M");
                assertThat(type.values()).extracting(ProductOptionValueModel::id).containsOnlyNulls();
                assertThat(type.values()).extracting(ProductOptionValueModel::priceDelta).containsExactly(0, M_PRICE_DELTA);
            });
        }
    }

    @Nested
    @DisplayName("성공 — 옵션 조합 생성")
    class GeneratesCombinations {

        @BeforeEach
        void stubHappyPath() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(productRepository.save(any())).willReturn(savedProduct());
            given(combinationRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("저장된 옵션값 id로 조합 2개를 만들고, 각 조합의 productId·키·옵션값·재고·판매여부를 채운다")
        void buildsCombinationPerOptionValue() {
            service.create(command());

            List<ProductOptionCombination> combinations = captureSavedCombinations();
            assertThat(combinations).allSatisfy(c -> {
                assertThat(c.productId()).isEqualTo(PRODUCT_ID);
                assertThat(c.isAvailable()).isTrue();
                assertThat(c.stock().stock()).isEqualTo(DEFAULT_STOCK);
                assertThat(c.stock().reservedStock()).isZero();
            });
            assertThat(combinations)
                    .extracting(c -> c.combinationKey().value())
                    .containsExactlyInAnyOrder(S_ID.toString(), M_ID.toString());
            assertThat(combinations)
                    .flatExtracting(ProductOptionCombination::optionValueIds)
                    .containsExactlyInAnyOrder(S_ID, M_ID);
        }

        @Test
        @DisplayName("조합 가격 = basePrice + 옵션값 priceDelta (S=10000, M=11000)")
        void computesCombinationPrice() {
            service.create(command());

            List<ProductOptionCombination> combinations = captureSavedCombinations();
            assertThat(combinations)
                    .filteredOn(c -> c.optionValueIds().contains(S_ID))
                    .singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(10_000));
            assertThat(combinations)
                    .filteredOn(c -> c.optionValueIds().contains(M_ID))
                    .singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(11_000));
        }

        @Test
        @DisplayName("상품을 먼저 저장한 뒤 조합을 저장한다 (옵션값 id 확정 순서)")
        void savesProductBeforeCombinations() {
            service.create(command());

            InOrder inOrder = inOrder(productRepository, combinationRepository);
            inOrder.verify(productRepository).save(any());
            inOrder.verify(combinationRepository).saveAll(any());
        }

        @Test
        @DisplayName("뷰는 저장된 상품 id와 상태명을 반환한다")
        void returnsView() {
            CreateProductView view = service.create(command());

            assertThat(view.productId()).isEqualTo(PRODUCT_ID);
            assertThat(view.status()).isEqualTo("PENDING_REVIEW");
        }
    }

    @Nested
    @DisplayName("옵션 없는 상품")
    class WithoutOptions {

        @Test
        @DisplayName("옵션 타입이 없으면 상품만 저장하고 조합은 만들지 않는다")
        void savesProductWithoutCombinations() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            Product savedNoOptions = Product.create(
                            SELLER_ID, CATEGORY_ID, "단일 상품", "설명", BASE_PRICE,
                            null, 0, ProductOptionModel.COMBINATION, NOW)
                    .toBuilder().id(PRODUCT_ID).build();
            given(productRepository.save(any())).willReturn(savedNoOptions);

            CreateProductView view = service.create(commandWith(List.of()));

            then(productRepository).should().save(any());
            then(combinationRepository).should(never()).saveAll(any());
            assertThat(view.productId()).isEqualTo(PRODUCT_ID);
        }
    }

    @Nested
    @DisplayName("카테고리 검증")
    class CategoryValidation {

        @Test
        @DisplayName("카테고리가 없으면 CategoryNotFoundException, 아무것도 저장하지 않는다")
        void rejectsWhenCategoryMissing() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(command()))
                    .isInstanceOf(CategoryNotFoundException.class);

            then(productRepository).shouldHaveNoInteractions();
            then(combinationRepository).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("태그 검증")
    class TagValidation {

        static Stream<List<String>> invalidTagSets() {
            return Stream.of(
                    List.of("여름!"),                                   // 특수문자
                    List.of("가".repeat(21)),                          // 21자 초과
                    List.of(" "),                                       // 공백
                    List.of("정상", "bad@tag"),                        // 일부만 위반
                    List.of("t1", "t2", "t3", "t4", "t5",
                            "t6", "t7", "t8", "t9", "t10", "t11"));    // 11개 초과
        }

        @ParameterizedTest
        @MethodSource("invalidTagSets")
        @DisplayName("규칙 위반 태그는 InvalidProductTagException, 카테고리 조회·저장 전에 차단한다")
        void rejectsInvalidTags(List<String> tags) {
            assertThatThrownBy(() -> service.create(commandWith(tags, sizeOptionCommand())))
                    .isInstanceOf(InvalidProductTagException.class);

            then(categoryRepository).shouldHaveNoInteractions();
            then(productRepository).shouldHaveNoInteractions();
            then(combinationRepository).shouldHaveNoInteractions();
        }

        static Stream<List<String>> validTagSets() {
            return Stream.of(
                    null,                                              // 태그 없음(null)
                    List.of(),                                         // 태그 없음(빈 리스트)
                    List.of("가".repeat(20)),                          // 경계: 정확히 20자
                    List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")); // 경계: 정확히 10개
        }

        @ParameterizedTest
        @MethodSource("validTagSets")
        @DisplayName("경계 내 태그는 예외 없이 등록된다")
        void acceptsValidTags(List<String> tags) {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(aCategory()));
            given(productRepository.save(any())).willReturn(savedProduct());
            given(combinationRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

            assertThatCode(() -> service.create(commandWith(tags, sizeOptionCommand())))
                    .doesNotThrowAnyException();

            then(productRepository).should().save(any());
        }
    }
}
