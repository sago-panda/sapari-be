package com.sapari.product.domain.model.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ProductImageRef VO")
class ProductImageRefTest {

  private static final UUID OWNER = UUID.randomUUID();

  @Nested
  @DisplayName("소유자 XOR (productId 또는 optionValueId 중 정확히 하나)")
  class OwnerXor {

    @Test
    @DisplayName("productId만 있으면 유효 (상품 소유)")
    void productOwned() {
      ProductImageRef ref = new ProductImageRef(null, OWNER, null, ImageRole.GALLERY, "img/1.jpg", (short) 1);
      assertThat(ref.productId()).isEqualTo(OWNER);
      assertThat(ref.optionValueId()).isNull();
    }

    @Test
    @DisplayName("optionValueId만 있으면 유효 (옵션값 소유)")
    void optionValueOwned() {
      ProductImageRef ref = new ProductImageRef(null, null, OWNER, ImageRole.SWATCH, "img/red.jpg", (short) 1);
      assertThat(ref.optionValueId()).isEqualTo(OWNER);
      assertThat(ref.productId()).isNull();
    }

    @Test
    @DisplayName("둘 다 null이면 IllegalArgumentException")
    void bothNull() {
      assertThatThrownBy(() -> new ProductImageRef(null, null, null, ImageRole.GALLERY, "img/1.jpg", (short) 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("둘 다 있으면 IllegalArgumentException")
    void bothPresent() {
      assertThatThrownBy(() -> new ProductImageRef(
          null, UUID.randomUUID(), UUID.randomUUID(), ImageRole.GALLERY, "img/1.jpg", (short) 1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("기타 불변식")
  class OtherInvariants {

    @Test
    @DisplayName("role이 null이면 IllegalArgumentException")
    void nullRole() {
      assertThatThrownBy(() -> new ProductImageRef(null, OWNER, null, null, "img/1.jpg", (short) 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("imageKey가 null·빈·공백이면 IllegalArgumentException")
    void blankImageKey(String imageKey) {
      assertThatThrownBy(() -> new ProductImageRef(null, OWNER, null, ImageRole.GALLERY, imageKey, (short) 1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("팩토리")
  class Factories {

    @Test
    @DisplayName("ofProduct: productId 세팅, optionValueId=null, id=null")
    void ofProduct() {
      ProductImageRef ref = ProductImageRef.ofProduct(OWNER, ImageRole.DETAIL, "img/d.jpg", (short) 2);
      assertThat(ref.id()).isNull();
      assertThat(ref.productId()).isEqualTo(OWNER);
      assertThat(ref.optionValueId()).isNull();
      assertThat(ref.role()).isEqualTo(ImageRole.DETAIL);
      assertThat(ref.imageKey()).isEqualTo("img/d.jpg");
      assertThat(ref.sortOrder()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("ofOptionValue: optionValueId 세팅, productId=null, id=null")
    void ofOptionValue() {
      ProductImageRef ref = ProductImageRef.ofOptionValue(OWNER, ImageRole.SWATCH, "img/s.jpg", (short) 1);
      assertThat(ref.id()).isNull();
      assertThat(ref.optionValueId()).isEqualTo(OWNER);
      assertThat(ref.productId()).isNull();
      assertThat(ref.role()).isEqualTo(ImageRole.SWATCH);
    }
  }
}
