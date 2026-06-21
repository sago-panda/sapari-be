package com.sapari.product.domain.model.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DiscountValue VO")
class DiscountValueTest {

  @Nested
  @DisplayName("유효 생성")
  class Valid {

    @ParameterizedTest(name = "RATE {0}%")
    @ValueSource(ints = {1, 50, 100})
    @DisplayName("RATE는 1~100 사이 값이면 생성된다")
    void rateWithinRange(int value) {
      DiscountValue dv = DiscountValue.of(DiscountType.RATE, value);
      assertThat(dv.type()).isEqualTo(DiscountType.RATE);
      assertThat(dv.value()).isEqualTo(value);
    }

    @Test
    @DisplayName("FIXED_AMOUNT는 100을 넘는 큰 금액도 허용된다")
    void fixedAmountAllowsLargeValue() {
      assertThatCode(() -> DiscountValue.of(DiscountType.FIXED_AMOUNT, 50_000))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("불변식 위반")
  class Invalid {

    @Test
    @DisplayName("type이 null이면 IllegalArgumentException")
    void rejectsNullType() {
      assertThatThrownBy(() -> new DiscountValue(null, 10))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "value={0}")
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("value가 0 이하면 IllegalArgumentException")
    void rejectsNonPositive(int value) {
      assertThatThrownBy(() -> new DiscountValue(DiscountType.FIXED_AMOUNT, value))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("value가 null이면 IllegalArgumentException")
    void rejectsNullValue() {
      assertThatThrownBy(() -> new DiscountValue(DiscountType.RATE, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "RATE {0}%")
    @ValueSource(ints = {101, 200})
    @DisplayName("RATE가 100을 초과하면 IllegalArgumentException")
    void rejectsRateOver100(int value) {
      assertThatThrownBy(() -> DiscountValue.of(DiscountType.RATE, value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
