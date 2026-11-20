package com.sapari.product.domain.model.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ProductOptionValueModel VO")
class ProductOptionValueModelTest {

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("value가 null·빈·공백이면 IllegalArgumentException")
  void rejectsBlankValue(String value) {
    assertThatThrownBy(() -> ProductOptionValueModel.create(value, null, null, 100, (short) 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("priceDelta가 null이면 0으로 정규화한다")
  void nullPriceDeltaBecomesZero() {
    assertThat(ProductOptionValueModel.create("빨강", null, null, null, (short) 1).priceDelta())
        .isZero();
  }

  @Test
  @DisplayName("create는 id를 null로 두고 나머지를 보관한다")
  void createHoldsFields() {
    UUID presetId = UUID.randomUUID();
    ProductOptionValueModel v =
        ProductOptionValueModel.create("파랑", presetId, "{\"hex\":\"#00F\"}", 500, (short) 2);
    assertThat(v.id()).isNull();
    assertThat(v.attributePresetId()).isEqualTo(presetId);
    assertThat(v.value()).isEqualTo("파랑");
    assertThat(v.metadata()).isEqualTo("{\"hex\":\"#00F\"}");
    assertThat(v.priceDelta()).isEqualTo(500);
    assertThat(v.sortOrder()).isEqualTo((short) 2);
  }
}
