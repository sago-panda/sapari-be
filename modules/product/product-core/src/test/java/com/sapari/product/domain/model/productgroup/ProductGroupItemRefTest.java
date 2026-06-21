package com.sapari.product.domain.model.productgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProductGroupItemRef VO")
class ProductGroupItemRefTest {

  @Test
  @DisplayName("productId가 null이면 IllegalArgumentException")
  void rejectsNullProductId() {
    assertThatThrownBy(() -> new ProductGroupItemRef(null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProductGroupItemRef.of(null, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("of: id는 null, sortOrder 누락 시 0으로 기본값")
  void ofDefaults() {
    UUID productId = UUID.randomUUID();
    ProductGroupItemRef ref = ProductGroupItemRef.of(productId, null);
    assertThat(ref.id()).isNull();
    assertThat(ref.productId()).isEqualTo(productId);
    assertThat(ref.sortOrder()).isZero();
  }

  @Test
  @DisplayName("of: sortOrder가 주어지면 그대로 보관")
  void ofKeepsSortOrder() {
    assertThat(ProductGroupItemRef.of(UUID.randomUUID(), 5).sortOrder()).isEqualTo(5);
  }
}
