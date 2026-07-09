package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.ChangeSaleStatusCommand;
import com.sapari.product.domain.exception.InvalidProductStateException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.repository.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
@DisplayName("ChangeProductSaleStatusService")
class ChangeProductSaleStatusServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Long CATEGORY_ID = 1001L;
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");
    private static final Long EXPECTED_VERSION = 7L;

    @Mock
    private ProductRepository productRepository;

    // 실제 TimeProvider를 고정 Clock으로 — now()는 LATER를 결정적으로 반환(스텁 불필요)
    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(LATER, ZoneOffset.UTC));

    private ChangeProductSaleStatusService service;

    @BeforeEach
    void setUp() {
        service = new ChangeProductSaleStatusService(productRepository, timeProvider);
    }

    private static Product create(UUID sellerId) {
        return Product.create(sellerId, CATEGORY_ID, "상품", "설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .toBuilder().id(PRODUCT_ID).build();
    }

    private static Product onSale() {
        return create(SELLER_ID).approve(NOW);
    }

    private static Product suspended() {
        return create(SELLER_ID).approve(NOW).suspend(NOW);
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
        @DisplayName("ON_SALE 상품을 SUSPENDED로 전환한다")
        void suspend() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSale()));
            given(productRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "SUSPENDED", EXPECTED_VERSION));

            Product saved = captureSaved();
            assertThat(saved.status()).isEqualTo(ProductStatus.SUSPENDED);
            assertThat(saved.updatedAt()).isEqualTo(LATER);
            // 클라이언트가 본 version으로 덮어써 stale 비교가 동작하도록 보장한다(§13)
            assertThat(saved.version()).isEqualTo(EXPECTED_VERSION);
        }

        @Test
        @DisplayName("SUSPENDED 상품을 ON_SALE로 전환한다")
        void resume() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(suspended()));
            given(productRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "ON_SALE", EXPECTED_VERSION));

            assertThat(captureSaved().status()).isEqualTo(ProductStatus.ON_SALE);
        }
    }

    @Nested
    @DisplayName("실패 — 저장하지 않는다")
    class Failure {

        @Test
        @DisplayName("허용되지 않는 target 값이면 InvalidProductStateException")
        void invalidTarget() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSale()));

            assertThatThrownBy(() -> service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "DELETED", EXPECTED_VERSION)))
                    .isInstanceOf(InvalidProductStateException.class);

            then(productRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("target이 null이면 NPE 없이 InvalidProductStateException")
        void nullTarget() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSale()));

            assertThatThrownBy(() -> service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, null, EXPECTED_VERSION)))
                    .isInstanceOf(InvalidProductStateException.class);

            then(productRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("허용되지 않는 전이면 InvalidProductStateException (PENDING_REVIEW → SUSPENDED)")
        void illegalTransition() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(create(SELLER_ID)));

            assertThatThrownBy(() -> service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "SUSPENDED", EXPECTED_VERSION)))
                    .isInstanceOf(InvalidProductStateException.class);

            then(productRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
        void notOwner() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(create(OTHER_SELLER).approve(NOW)));

            assertThatThrownBy(() -> service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "SUSPENDED", EXPECTED_VERSION)))
                    .isInstanceOf(ProductAccessDeniedException.class);

            then(productRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("상품이 없으면 ProductNotFoundException")
        void productMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "SUSPENDED", EXPECTED_VERSION)))
                    .isInstanceOf(ProductNotFoundException.class);

            then(productRepository).should(never()).save(any());
        }
    }
}
