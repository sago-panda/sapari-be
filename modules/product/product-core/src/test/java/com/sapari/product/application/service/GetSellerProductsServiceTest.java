package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.common.page.Cursor;
import com.sapari.common.page.CursorCodec;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.view.ProductSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetSellerProductsService")
class GetSellerProductsServiceTest {

    private static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_1 = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID PRODUCT_2 = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    /** 마이크로초 자릿수까지 있는 등록 일시 — DB timestamptz 정밀도와 같다. */
    private static final Instant CREATED_AT = Instant.parse("2026-11-04T10:15:30.123456Z");
    private static final long CREATED_AT_MICROS = 1_793_787_330_123_456L;
    private static final long VERSION = 3L;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private GetSellerProductsService service;

    /**
     * 지정한 id·등록 일시를 가진 요약 read-model. 나머지 스칼라는 FixtureMonkey가 채운다.
     */
    private static ProductSummary summary(UUID id, Instant createdAt) {
        return FM.giveMeBuilder(ProductSummary.class)
                .set("id", id)
                .set("createdAt", createdAt)
                .sample();
    }

    /**
     * 서로 다른 id·등록 일시를 가진 요약 n개(등록 일시 내림차순).
     */
    private static List<ProductSummary> summaries(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> summary(UUID.randomUUID(), CREATED_AT.minusSeconds(i)))
                .toList();
    }

    @Nested
    @DisplayName("뷰 매핑")
    class Mapping {

        @Test
        @DisplayName("요약 read-model의 모든 필드를 목록 뷰로 옮긴다(상태 enum은 이름 문자열)")
        void mapsSummaryToView() {
            given(productRepository.findActiveSellerProductSummaries(eq(SELLER_ID), isNull(), isNull(), anyInt()))
                    .willReturn(List.of(new ProductSummary(PRODUCT_1, "상품 1", ProductStatus.ON_SALE, 9_000, true, 5,
                            new BigDecimal("4.2"), "g0.jpg", VERSION, CREATED_AT)));

            CursorPage<ProductSummaryView> page = service.get(new GetSellerProductsCommand(SELLER_ID, null, null));

            assertThat(page.content()).singleElement().satisfies(view -> {
                assertThat(view.productId()).isEqualTo(PRODUCT_1);
                assertThat(view.name()).isEqualTo("상품 1");
                assertThat(view.status()).isEqualTo("ON_SALE");
                assertThat(view.minPrice()).isEqualTo(9_000);
                assertThat(view.hasStock()).isTrue();
                assertThat(view.reviewCount()).isEqualTo(5);
                assertThat(view.avgRating()).isEqualByComparingTo("4.2");
                assertThat(view.thumbnailKey()).isEqualTo("g0.jpg");
                assertThat(view.version()).isEqualTo(VERSION);
            });
        }

        @Test
        @DisplayName("nullable 값(thumbnailKey·minPrice·avgRating)은 그대로 전달한다")
        void passesThroughNullables() {
            given(productRepository.findActiveSellerProductSummaries(eq(SELLER_ID), isNull(), isNull(), anyInt()))
                    .willReturn(List.of(new ProductSummary(
                            PRODUCT_1, "상품", ProductStatus.ON_SALE, null, false, 0, null, null, VERSION, CREATED_AT)));

            ProductSummaryView view = service.get(new GetSellerProductsCommand(SELLER_ID, null, null)).content().get(0);

            assertThat(view.thumbnailKey()).isNull();
            assertThat(view.minPrice()).isNull();
            assertThat(view.avgRating()).isNull();
        }

        @Test
        @DisplayName("여러 건은 repository 반환 순서를 보존해 매핑한다")
        void preservesOrder() {
            given(productRepository.findActiveSellerProductSummaries(eq(SELLER_ID), isNull(), isNull(), anyInt()))
                    .willReturn(List.of(summary(PRODUCT_1, CREATED_AT), summary(PRODUCT_2, CREATED_AT.minusSeconds(1))));

            assertThat(service.get(new GetSellerProductsCommand(SELLER_ID, null, null)).content())
                    .extracting(ProductSummaryView::productId)
                    .containsExactly(PRODUCT_1, PRODUCT_2);
        }
    }

    @Nested
    @DisplayName("페이지 판정")
    class Paging {

        @Test
        @DisplayName("첫 페이지는 위치 없이(null, null) size + 1개를 요청한다")
        void firstPageRequestsSizePlusOneWithoutPosition() {
            given(productRepository.findActiveSellerProductSummaries(any(), any(), any(), anyInt())).willReturn(List.of());

            service.get(new GetSellerProductsCommand(SELLER_ID, null, 20));

            then(productRepository).should().findActiveSellerProductSummaries(SELLER_ID, null, null, 21);
            then(productRepository).shouldHaveNoMoreInteractions();
        }

        @ParameterizedTest(name = "size={0} → limit={1}")
        @CsvSource(value = {"null, 16", "0, 16", "-5, 16", "1, 2", "100, 101", "101, 101", "1000000, 101"},
                nullValues = "null")
        @DisplayName("size는 정규화(기본 15, 상한 100)한 뒤 + 1로 조회한다 — 클라이언트 값이 쿼리에 그대로 가지 않는다")
        void normalizesSize(Integer requested, int expectedLimit) {
            given(productRepository.findActiveSellerProductSummaries(any(), any(), any(), anyInt())).willReturn(List.of());

            service.get(new GetSellerProductsCommand(SELLER_ID, null, requested));

            then(productRepository).should().findActiveSellerProductSummaries(SELLER_ID, null, null, expectedLimit);
        }

        @Test
        @DisplayName("size + 1개가 오면 다음 페이지가 있다 — 잉여 1개를 버리고 마지막 행으로 커서를 만든다")
        void hasNextTrimsExtraAndEncodesLastRow() {
            List<ProductSummary> rows = summaries(3);
            given(productRepository.findActiveSellerProductSummaries(SELLER_ID, null, null, 3)).willReturn(rows);

            CursorPage<ProductSummaryView> page = service.get(new GetSellerProductsCommand(SELLER_ID, null, 2));

            assertThat(page.hasNext()).isTrue();
            assertThat(page.content()).extracting(ProductSummaryView::productId)
                    .containsExactly(rows.get(0).id(), rows.get(1).id());
            // 커서는 잘려 나간 rows[2]가 아니라 노출된 마지막 행(rows[1])을 가리켜야 한다
            ProductSummary last = rows.get(1);
            Cursor cursor = CursorCodec.decode(page.nextCursor());
            assertThat(cursor.idAsUuid()).isEqualTo(last.id());
            assertThat(Instant.EPOCH.plus(cursor.sortKeyAsLong(), ChronoUnit.MICROS)).isEqualTo(last.createdAt());
        }

        @Test
        @DisplayName("size 이하로 오면 마지막 페이지다 — hasNext=false, nextCursor=null, 그대로 노출")
        void lastPageHasNoCursor() {
            List<ProductSummary> rows = summaries(2);
            given(productRepository.findActiveSellerProductSummaries(SELLER_ID, null, null, 3)).willReturn(rows);

            CursorPage<ProductSummaryView> page = service.get(new GetSellerProductsCommand(SELLER_ID, null, 2));

            assertThat(page.hasNext()).isFalse();
            assertThat(page.nextCursor()).isNull();
            assertThat(page.content()).hasSize(2);
        }

        @Test
        @DisplayName("결과가 없으면 빈 마지막 페이지다")
        void emptyPage() {
            given(productRepository.findActiveSellerProductSummaries(SELLER_ID, null, null, 16)).willReturn(List.of());

            CursorPage<ProductSummaryView> page = service.get(new GetSellerProductsCommand(SELLER_ID, null, null));

            assertThat(page.content()).isEmpty();
            assertThat(page.hasNext()).isFalse();
            assertThat(page.nextCursor()).isNull();
        }
    }

    @Nested
    @DisplayName("커서 왕복")
    class CursorRoundTrip {

        @Test
        @DisplayName("커서 정렬키는 등록 일시의 epoch 마이크로초다 — 마이크로초 자릿수까지 보존한다")
        void encodesCreatedAtAsEpochMicros() {
            given(productRepository.findActiveSellerProductSummaries(SELLER_ID, null, null, 2))
                    .willReturn(List.of(summary(PRODUCT_1, CREATED_AT), summary(PRODUCT_2, CREATED_AT.minusSeconds(1))));

            String nextCursor = service.get(new GetSellerProductsCommand(SELLER_ID, null, 1)).nextCursor();

            assertThat(nextCursor).isEqualTo(CursorCodec.encode(String.valueOf(CREATED_AT_MICROS), PRODUCT_1.toString()));
        }

        @Test
        @DisplayName("받은 커서를 다시 보내면 그 행의 (등록 일시, id)를 정확히 다음 위치로 넘긴다")
        void cursorDecodesToExactPosition() {
            String cursor = CursorCodec.encode(String.valueOf(CREATED_AT_MICROS), PRODUCT_1.toString());
            given(productRepository.findActiveSellerProductSummaries(any(), any(), any(), anyInt())).willReturn(List.of());

            service.get(new GetSellerProductsCommand(SELLER_ID, cursor, 10));

            then(productRepository).should().findActiveSellerProductSummaries(SELLER_ID, CREATED_AT, PRODUCT_1, 11);
        }

        @Test
        @DisplayName("한 페이지의 nextCursor로 다음 페이지를 요청하면 직전 마지막 행 위치가 그대로 전달된다")
        void nextCursorFeedsNextPage() {
            List<ProductSummary> firstRows = summaries(3);
            given(productRepository.findActiveSellerProductSummaries(SELLER_ID, null, null, 3)).willReturn(firstRows);
            String nextCursor = service.get(new GetSellerProductsCommand(SELLER_ID, null, 2)).nextCursor();
            ProductSummary last = firstRows.get(1);
            given(productRepository.findActiveSellerProductSummaries(SELLER_ID, last.createdAt(), last.id(), 3))
                    .willReturn(List.of(firstRows.get(2)));

            CursorPage<ProductSummaryView> second = service.get(new GetSellerProductsCommand(SELLER_ID, nextCursor, 2));

            assertThat(second.content()).extracting(ProductSummaryView::productId).containsExactly(firstRows.get(2).id());
            assertThat(second.hasNext()).isFalse();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource({
                "not-base64!!",
                "MTIzNDU2",                       // 구분자 없음("123456")
                "YWJjfDAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDBmMQ", // 정렬키가 숫자가 아님("abc|...")
                "MTIzfG5vdC1hLXV1aWQ"             // id가 UUID가 아님("123|not-a-uuid")
        })
        @DisplayName("손상된 커서는 InvalidCursorException(400)으로 거절하고 조회하지 않는다")
        void rejectsInvalidCursor(String cursor) {
            assertThatThrownBy(() -> service.get(new GetSellerProductsCommand(SELLER_ID, cursor, 10)))
                    .isInstanceOf(InvalidCursorException.class);

            then(productRepository).shouldHaveNoInteractions();
        }
    }
}
