package com.sapari.product.domain.model.optionattribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OptionPreset VO")
class OptionPresetTest {

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("value가 null·빈·공백이면 IllegalArgumentException")
  void rejectsBlankValue(String value) {
    assertThatThrownBy(() -> new OptionPreset(null, value, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OptionPreset.of(value, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("of: id는 null, sortOrder 누락 시 0으로 기본값")
  void ofDefaults() {
    OptionPreset preset = OptionPreset.of("빨강", null);
    assertThat(preset.id()).isNull();
    assertThat(preset.value()).isEqualTo("빨강");
    assertThat(preset.sortOrder()).isZero();
  }

  @Test
  @DisplayName("of: sortOrder가 주어지면 그대로 보관")
  void ofKeepsSortOrder() {
    assertThat(OptionPreset.of("M", 3).sortOrder()).isEqualTo(3);
  }
}
