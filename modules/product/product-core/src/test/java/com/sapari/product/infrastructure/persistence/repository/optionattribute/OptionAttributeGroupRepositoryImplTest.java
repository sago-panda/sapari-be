package com.sapari.product.infrastructure.persistence.repository.optionattribute;

import static com.sapari.product.support.ProductFixtures.SELLER_A;
import static com.sapari.product.support.ProductFixtures.SELLER_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.optionattribute.OptionAttributeGroup;
import com.sapari.product.domain.model.optionattribute.OptionPreset;
import com.sapari.product.domain.repository.optionattribute.OptionAttributeGroupRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link OptionAttributeGroupRepositoryImpl} 통합 테스트. 그룹 + 자식 presets upsert/교체, system/custom 구분.
 */
@DisplayName("OptionAttributeGroupRepositoryImpl 통합 테스트")
class OptionAttributeGroupRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    OptionAttributeGroupRepository repository;

    @PersistenceContext
    EntityManager em;

    @Nested
    @DisplayName("신규 저장(INSERT)")
    class SaveNew {

        @Test
        @DisplayName("시스템 그룹 + presets를 함께 저장하고 정렬해 조립한다")
        void system_group_with_presets() {
            OptionAttributeGroup saved = repository.save(OptionAttributeGroup.create(
                    "색상", true, null, "색상 속성",
                    List.of(OptionPreset.of("빨강", 1), OptionPreset.of("파랑", 2))));
            em.flush();
            em.clear();

            OptionAttributeGroup reloaded = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(reloaded.name()).isEqualTo("색상");
            assertThat(reloaded.isSystem()).isTrue();
            assertThat(reloaded.sellerId()).isNull();
            assertThat(reloaded.description()).isEqualTo("색상 속성");
            assertThat(reloaded.presets()).extracting(OptionPreset::value)
                    .containsExactlyInAnyOrder("빨강", "파랑");
        }

        @Test
        @DisplayName("판매자 커스텀 그룹은 sellerId를 보유한다")
        void custom_group_has_seller() {
            OptionAttributeGroup saved = repository.save(
                    OptionAttributeGroup.create("내 사이즈", false, SELLER_A, null, List.of()));
            em.flush();
            em.clear();

            OptionAttributeGroup reloaded = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(reloaded.isSystem()).isFalse();
            assertThat(reloaded.sellerId()).isEqualTo(SELLER_A);
            assertThat(reloaded.presets()).isEmpty();
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("presets를 전체 교체한다(옛 preset 삭제 후 재삽입)")
        void replaces_presets() {
            OptionAttributeGroup saved = repository.save(OptionAttributeGroup.create(
                    "색상", true, null, null,
                    List.of(OptionPreset.of("빨강", 1), OptionPreset.of("파랑", 2))));
            em.flush();
            em.clear();

            OptionAttributeGroup updated = repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .presets(List.of(OptionPreset.of("검정", 1)))
                    .build();
            repository.save(updated);
            em.flush();
            em.clear();

            OptionAttributeGroup after = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.presets()).extracting(OptionPreset::value)
                    .containsExactly("검정");
        }
    }

    @Nested
    @DisplayName("조회")
    class Finders {

        @Test
        @DisplayName("findBySellerId는 해당 판매자 커스텀 그룹만, findSystemGroups는 시스템 그룹만 반환한다")
        void seller_and_system_filters() {
            OptionAttributeGroup systemGroup =
                    repository.save(OptionAttributeGroup.create("색상", true, null, null, List.of()));
            OptionAttributeGroup customA =
                    repository.save(OptionAttributeGroup.create("A커스텀", false, SELLER_A, null, List.of()));
            repository.save(OptionAttributeGroup.create("B커스텀", false, SELLER_B, null, List.of()));
            em.flush();
            em.clear();

            assertThat(repository.findBySellerId(SELLER_A)).extracting(OptionAttributeGroup::id)
                    .containsExactly(customA.id());
            assertThat(repository.findSystemGroups()).extracting(OptionAttributeGroup::id)
                    .contains(systemGroup.id())
                    .doesNotContain(customA.id());
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(java.util.UUID.randomUUID())).isEmpty();
        }
    }
}
