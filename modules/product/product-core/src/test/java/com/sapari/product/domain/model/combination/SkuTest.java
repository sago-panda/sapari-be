package com.sapari.product.domain.model.combination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Sku VO")
class SkuTest {

  @Nested
  @DisplayName("생성자")
  class Constructor {

    @Test
    @DisplayName("값이 있으면 그대로 보관한다")
    void holdsValue() {
      assertThat(new Sku("ABC-001").value()).isEqualTo("ABC-001");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("null·빈·공백 값이면 IllegalArgumentException")
    void rejectsBlank(String value) {
      assertThatThrownBy(() -> new Sku(value)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("of 팩토리")
  class Factory {

    @Test
    @DisplayName("null 입력은 null Sku로 표현한다(값 없음)")
    void ofNullReturnsNull() {
      assertThat(Sku.of(null)).isNull();
    }

    @Test
    @DisplayName("값이 있으면 Sku를 만든다")
    void ofValueCreates() {
      assertThat(Sku.of("SKU-9").value()).isEqualTo("SKU-9");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @EmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("빈·공백 값(비 null)이면 IllegalArgumentException")
    void ofBlankThrows(String value) {
      assertThatThrownBy(() -> Sku.of(value)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
