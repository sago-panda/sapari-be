package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
