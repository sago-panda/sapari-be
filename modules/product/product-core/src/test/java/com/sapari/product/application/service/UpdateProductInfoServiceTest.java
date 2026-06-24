package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.UpdateProductInfoCommand;
import com.sapari.product.domain.exception.CategoryNotFoundException;
import com.sapari.product.domain.exception.InvalidProductTagException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.category.Category;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.repository.category.CategoryRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateProductInfoService")
class UpdateProductInfoServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID SHIPPING_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Long OLD_CATEGORY = 1001L;
    private static final Long NEW_CATEGORY = 2002L;
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    // 실제 TimeProvider를 고정 Clock으로 — now()는 LATER를 결정적으로 반환(스텁 불필요)
    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(LATER, ZoneOffset.UTC));

    private UpdateProductInfoService service;

    @BeforeEach
    void setUp() {
        service = new UpdateProductInfoService(productRepository, categoryRepository, timeProvider);
    }

    private static Category aCategory() {
        return Category.create(null, "2002", "신발", (short) 1, 0, true);
    }

    private static Product onSaleProduct() {
        return Product.create(
                        SELLER_ID, OLD_CATEGORY, "원래 상품", "원래 설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW)
                .toBuilder()
                .id(PRODUCT_ID)
                .tags(List.of("여름"))
                .build();
    }

    private static UpdateProductInfoCommand command() {
        return new UpdateProductInfoCommand(
                PRODUCT_ID, SELLER_ID, NEW_CATEGORY, "수정 상품", "수정 설명", SHIPPING_ID, 3_000, List.of("가을"));
    }

    private static UpdateProductInfoCommand commandWithTags(List<String> tags) {
        return new UpdateProductInfoCommand(
                PRODUCT_ID, SELLER_ID, NEW_CATEGORY, "수정 상품", "수정 설명", SHIPPING_ID, 3_000, tags);
    }

    private Product captureSaved() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        then(productRepository).should().save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("대상 필드를 갱신하고 PENDING_REVIEW로 전환하며 태그를 교체한다 (가격·생성시각 보존)")
        void updatesInfoAndMovesToReview() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct()));
            given(categoryRepository.findById(NEW_CATEGORY)).willReturn(Optional.of(aCategory()));
            given(productRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command());

            Product saved = captureSaved();
            assertThat(saved.id()).isEqualTo(PRODUCT_ID);
            assertThat(saved.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(saved.categoryId()).isEqualTo(NEW_CATEGORY);
            assertThat(saved.name()).isEqualTo("수정 상품");
            assertThat(saved.description()).isEqualTo("수정 설명");
            assertThat(saved.shippingPolicyId()).isEqualTo(SHIPPING_ID);
            assertThat(saved.additionalShippingFee()).isEqualTo(3_000);
            assertThat(saved.tags()).containsExactly("가을");
            assertThat(saved.updatedAt()).isEqualTo(LATER);
            assertThat(saved.basePrice()).isEqualTo(10_000);
            assertThat(saved.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("반려 상품을 수정하면 rejectionReason이 비워지고 재검수 대기가 된다")
        void clearsRejectionReasonOnResubmit() {
            Product rejected = onSaleProduct().reject("이미지 부적절", NOW);
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(rejected));
            given(categoryRepository.findById(NEW_CATEGORY)).willReturn(Optional.of(aCategory()));
            given(productRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command());

            Product saved = captureSaved();
            assertThat(saved.status()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(saved.rejectionReason()).isNull();
        }

        @Test
        @DisplayName("태그가 null이면 빈 태그로 교체한다")
        void replacesWithEmptyTagsWhenNull() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct()));
            given(categoryRepository.findById(NEW_CATEGORY)).willReturn(Optional.of(aCategory()));
            given(productRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(commandWithTags(null));

            assertThat(captureSaved().tags()).isEmpty();
        }
    }

    @Nested
    @DisplayName("실패 — 저장하지 않는다")
    class Failure {

        @Test
        @DisplayName("상품이 없으면 ProductNotFoundException")
        void productMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductNotFoundException.class);

            then(productRepository).should(never()).save(any());
            then(categoryRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
        void notOwner() {
            Product othersProduct = Product.create(
                            OTHER_SELLER, OLD_CATEGORY, "남의 상품", "설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                    .approve(NOW).toBuilder().id(PRODUCT_ID).build();
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(othersProduct));

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductAccessDeniedException.class);

            then(productRepository).should(never()).save(any());
            then(categoryRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("변경할 카테고리가 없으면 CategoryNotFoundException")
        void categoryMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct()));
            given(categoryRepository.findById(NEW_CATEGORY)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(CategoryNotFoundException.class);

            then(productRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("태그 규칙을 위반하면 InvalidProductTagException")
        void invalidTag() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct()));

            assertThatThrownBy(() -> service.update(commandWithTags(List.of("가을!"))))
                    .isInstanceOf(InvalidProductTagException.class);

            then(productRepository).should(never()).save(any());
            then(categoryRepository).shouldHaveNoInteractions();
        }
    }
}
