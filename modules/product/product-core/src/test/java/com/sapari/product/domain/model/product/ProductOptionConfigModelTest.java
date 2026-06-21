package com.sapari.product.domain.model.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProductOptionConfigModel VO")
class ProductOptionConfigModelTest {

  @Test
  @DisplayName("of는 두 jsonb 룰 문자열을 그대로 보관한다")
  void ofRoundTrips() {
    ProductOptionConfigModel config = ProductOptionConfigModel.of(
        "[{\"valueIds\":[\"v1\"],\"amount\":1000}]", "[{\"valueIds\":[\"v2\"]}]");
    assertThat(config.surchargeRules()).isEqualTo("[{\"valueIds\":[\"v1\"],\"amount\":1000}]");
    assertThat(config.exclusionRules()).isEqualTo("[{\"valueIds\":[\"v2\"]}]");
  }

  @Test
  @DisplayName("불변식이 없어 null 룰도 허용한다(룰 없음)")
  void allowsNulls() {
    ProductOptionConfigModel config = ProductOptionConfigModel.of(null, null);
    assertThat(config.surchargeRules()).isNull();
    assertThat(config.exclusionRules()).isNull();
  }
}
