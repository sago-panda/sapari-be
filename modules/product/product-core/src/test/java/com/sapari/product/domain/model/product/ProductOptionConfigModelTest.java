package com.sapari.product.domain.model.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.SurchargeRule;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProductOptionConfigModel VO")
class ProductOptionConfigModelTest {

    private static final SurchargeRule SURCHARGE = new SurchargeRule(List.of(UUID.randomUUID()), 1_000);
    private static final ExclusionRule EXCLUSION = new ExclusionRule(List.of(UUID.randomUUID()), "미생산");

    @Test
    @DisplayName("of는 두 룰 목록을 그대로 보관한다")
    void ofKeepsRules() {
        ProductOptionConfigModel config = ProductOptionConfigModel.of(List.of(SURCHARGE), List.of(EXCLUSION));

        assertThat(config.surchargeRules()).containsExactly(SURCHARGE);
        assertThat(config.exclusionRules()).containsExactly(EXCLUSION);
    }

    @Test
    @DisplayName("null 목록은 빈 목록으로 정규화한다(룰 없음)")
    void normalizesNullToEmpty() {
        ProductOptionConfigModel config = ProductOptionConfigModel.of(null, null);

        assertThat(config.surchargeRules()).isEmpty();
        assertThat(config.exclusionRules()).isEmpty();
    }

    @Test
    @DisplayName("방어적으로 복사해 원본 목록 변경이 반영되지 않고, 보관 목록은 수정할 수 없다")
    void copiesDefensively() {
        List<SurchargeRule> source = new ArrayList<>(List.of(SURCHARGE));
        ProductOptionConfigModel config = ProductOptionConfigModel.of(source, List.of());

        source.clear();

        assertThat(config.surchargeRules()).containsExactly(SURCHARGE);
        assertThatThrownBy(() -> config.surchargeRules().add(SURCHARGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
