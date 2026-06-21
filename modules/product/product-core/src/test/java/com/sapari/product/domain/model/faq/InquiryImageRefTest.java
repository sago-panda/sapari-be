package com.sapari.product.domain.model.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("InquiryImageRef VO")
class InquiryImageRefTest {

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("imageKey가 null·빈·공백이면 IllegalArgumentException")
  void rejectsBlankImageKey(String imageKey) {
    assertThatThrownBy(() -> new InquiryImageRef(null, imageKey, "원본.png", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InquiryImageRef.of(imageKey, "원본.png", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("of: id는 null로 두고 나머지를 보관한다(originFileName·sortOrder는 그대로)")
  void ofHoldsFields() {
    InquiryImageRef ref = InquiryImageRef.of("faq/img.png", "스크린샷.png", 2);
    assertThat(ref.id()).isNull();
    assertThat(ref.imageKey()).isEqualTo("faq/img.png");
    assertThat(ref.originFileName()).isEqualTo("스크린샷.png");
    assertThat(ref.sortOrder()).isEqualTo(2);
  }

  @Test
  @DisplayName("originFileName은 null이어도 허용된다")
  void allowsNullOriginFileName() {
    assertThat(InquiryImageRef.of("faq/img.png", null, 0).originFileName()).isNull();
  }
}
