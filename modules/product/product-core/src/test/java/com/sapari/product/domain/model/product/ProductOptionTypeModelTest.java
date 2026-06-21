package com.sapari.product.domain.model.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ProductOptionTypeModel VO")
class ProductOptionTypeModelTest {

  private static ProductOptionValueModel value(String v) {
    return ProductOptionValueModel.create(v, null, null, 0, (short) 1);
  }

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("name이 null·빈·공백이면 IllegalArgumentException")
  void rejectsBlankName(String name) {
    assertThatThrownBy(() -> ProductOptionTypeModel.create(name, null, (short) 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("values가 null이면 빈 리스트로 정규화한다")
  void nullValuesBecomeEmpty() {
    ProductOptionTypeModel type = ProductOptionTypeModel.create("색상", null, (short) 1, null);
    assertThat(type.values()).isEmpty();
  }

  @Test
  @DisplayName("values는 방어 복사된 불변 리스트다 (원본 변경·반환 리스트 변경 모두 무관/불가)")
  void valuesAreImmutableCopy() {
    List<ProductOptionValueModel> src = new ArrayList<>();
    src.add(value("빨강"));
    ProductOptionTypeModel type = ProductOptionTypeModel.create("색상", null, (short) 1, src);

    src.add(value("파랑"));                                  // 원본 변경이 VO에 영향 없음
    assertThat(type.values()).hasSize(1);
    assertThatThrownBy(() -> type.values().add(value("초록"))) // 반환 리스트는 불변
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("create는 id를 null로 두고(영속 시 부여) 나머지를 보관한다")
  void createLeavesIdNull() {
    UUID groupId = UUID.randomUUID();
    ProductOptionTypeModel type = ProductOptionTypeModel.create("사이즈", groupId, (short) 2, List.of(value("M")));
    assertThat(type.id()).isNull();
    assertThat(type.attributeGroupId()).isEqualTo(groupId);
    assertThat(type.name()).isEqualTo("사이즈");
    assertThat(type.sortOrder()).isEqualTo((short) 2);
    assertThat(type.values()).extracting(ProductOptionValueModel::value).containsExactly("M");
  }
}
