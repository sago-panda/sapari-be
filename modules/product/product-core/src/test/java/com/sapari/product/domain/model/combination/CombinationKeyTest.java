package com.sapari.product.domain.model.combination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CombinationKey VO")
class CombinationKeyTest {

  @Test
  @DisplayName("옵션값 ID 조합 문자열을 보관한다")
  void holdsValue() {
    assertThat(CombinationKey.of("3:7:12").value()).isEqualTo("3:7:12");
    assertThat(new CombinationKey("1").value()).isEqualTo("1");
  }

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("null·빈·공백이면 IllegalArgumentException (생성자/of 모두)")
  void rejectsBlank(String value) {
    assertThatThrownBy(() -> new CombinationKey(value)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CombinationKey.of(value)).isInstanceOf(IllegalArgumentException.class);
  }
}
