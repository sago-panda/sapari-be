package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.DeleteProductCommand;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.repository.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteProductService")
class DeleteProductServiceTest {

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

    private DeleteProductService service;

    @BeforeEach
    void setUp() {
        service = new DeleteProductService(productRepository, timeProvider);
    }

    private static Product product(UUID sellerId) {
        return Product.create(sellerId, CATEGORY_ID, "상품", "설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW).toBuilder().id(PRODUCT_ID).build();
    }

    @Test
    @DisplayName("성공: 소프트 삭제(deletedAt 기록)하고 저장한다")
    void softDeletes() {
        given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
        given(productRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.delete(new DeleteProductCommand(PRODUCT_ID, SELLER_ID, EXPECTED_VERSION));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        then(productRepository).should().save(captor.capture());
        Product saved = captor.getValue();
        assertThat(saved.isDeleted()).isTrue();
        assertThat(saved.deletedAt()).isEqualTo(LATER);
        // 클라이언트가 본 version으로 덮어써 stale 비교가 동작하도록 보장한다(§13)
        assertThat(saved.version()).isEqualTo(EXPECTED_VERSION);
    }

    @Test
    @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
    void notOwner() {
        given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(OTHER_SELLER)));

        assertThatThrownBy(() -> service.delete(new DeleteProductCommand(PRODUCT_ID, SELLER_ID, EXPECTED_VERSION)))
                .isInstanceOf(ProductAccessDeniedException.class);

        then(productRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("상품이 없으면 ProductNotFoundException")
    void productMissing() {
        given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(new DeleteProductCommand(PRODUCT_ID, SELLER_ID, EXPECTED_VERSION)))
                .isInstanceOf(ProductNotFoundException.class);

        then(productRepository).should(never()).save(any());
    }
}
