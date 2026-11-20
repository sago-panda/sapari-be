package com.sapari.product.domain.model.combination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Stock VO")
class StockTest {

  @Nested
  @DisplayName("불변식 (0 <= reserved <= stock)")
  class Invariants {

    @ParameterizedTest(name = "stock={0}, reserved={1}")
    @CsvSource({"0, 0", "10, 0", "10, 10", "10, 3", "100, 99"})
    @DisplayName("유효한 조합은 생성된다")
    void validCombinations(int stock, int reserved) {
      assertThatCode(() -> Stock.of(stock, reserved)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stock null 또는 음수면 IllegalArgumentException")
    void rejectsBadStock() {
      assertThatThrownBy(() -> new Stock(null, 0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new Stock(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reservedStock null 또는 음수면 IllegalArgumentException")
    void rejectsBadReserved() {
      assertThatThrownBy(() -> new Stock(10, null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new Stock(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reservedStock > stock 이면 IllegalArgumentException")
    void rejectsReservedOverStock() {
      assertThatThrownBy(() -> new Stock(5, 6)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("availableStock()")
  class Available {

    @ParameterizedTest(name = "stock={0}, reserved={1} -> {2}")
    @CsvSource({"100, 0, 100", "100, 30, 70", "10, 10, 0", "1, 0, 1"})
    @DisplayName("가용 재고 = stock - reservedStock (경계 reserved==stock → 0 포함)")
    void computesAvailable(int stock, int reserved, int expected) {
      assertThat(Stock.of(stock, reserved).availableStock()).isEqualTo(expected);
    }
  }
}
