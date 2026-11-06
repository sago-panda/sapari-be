package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sapari.live.domain.exception.InvalidLiveProductException;
import com.sapari.live.domain.exception.LiveErrorCode;

class LiveProductTest {

    private static final Instant MARKER = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("고정 상품인데 pinnedAt이 없으면 생성에 실패한다.")
    void create_fails_when_pinned_without_pinnedAt() {
        assertThatThrownBy(() -> LiveProduct.create(
                UUID.randomUUID(), UUID.randomUUID(), 10000, 8000, 7000, true, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinnedAt");
    }

    @Test
    @DisplayName("고정 상품은 pinnedAt이 있으면 정상 생성된다.")
    void create_succeeds_when_pinned_with_pinnedAt() {
        assertThatCode(() -> LiveProduct.create(
                UUID.randomUUID(), UUID.randomUUID(), 10000, 8000, 7000, true, 0, MARKER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("고정 해제된 상품도 pinnedAt을 가질 수 있다 — VOD 다시보기 마커.")
    void create_allows_unpinned_with_pinnedAt() {
        LiveProduct product = LiveProduct.create(
                UUID.randomUUID(), UUID.randomUUID(), 10000, 8000, 7000, false, 2, MARKER);

        assertThat(product.isPinned()).isFalse();
        assertThat(product.pinnedAt()).isEqualTo(MARKER);
    }

    @ParameterizedTest(name = "정가={0}, 할인가={1}, 라이브 할인가={2}")
    @CsvSource({
            "10000, 12000, 7000",   // 할인가 > 정가
            "10000, 8000, 9000",    // 라이브 할인가 > 할인가
            "0, 0, 0",              // 정가 0
            "10000, -1, 0",         // 음수 할인가
            "10000, 8000, -1"       // 음수 라이브 할인가
    })
    @DisplayName("가격 규칙 위반은 판매자 입력 오류(LIVE-008)다 — 500 으로 새는 IAE 가 아니다.")
    void create_rejects_price_violation_as_client_error(int original, int discount, int liveDiscount) {
        assertThatThrownBy(() -> LiveProduct.create(
                UUID.randomUUID(), UUID.randomUUID(), original, discount, liveDiscount, false, 0, null))
                .isInstanceOf(InvalidLiveProductException.class)
                .extracting(e -> ((InvalidLiveProductException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.INVALID_LIVE_PRODUCT_PRICE);
    }

    @Test
    @DisplayName("가격이 같아도 된다 — 정가 = 할인가 = 라이브 할인가.")
    void validatePrices_allows_equal_prices() {
        assertThatCode(() -> LiveProduct.validatePrices(10000, 10000, 10000)).doesNotThrowAnyException();
    }
}
