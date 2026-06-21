package com.sapari.product.infrastructure.persistence.repository.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.sapari.product.domain.model.discount.DiscountPolicy;
import com.sapari.product.domain.model.discount.DiscountType;
import com.sapari.product.domain.model.discount.DiscountValue;
import com.sapari.product.domain.repository.discount.DiscountPolicyRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link DiscountPolicyRepositoryImpl} 통합 테스트. 루트(DiscountValue VO) + 대상 매핑(productIds/combinationIds) upsert/교체.
 */
@DisplayName("DiscountPolicyRepositoryImpl 통합 테스트")
class DiscountPolicyRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-12-31T00:00:00Z");

    @Autowired
    DiscountPolicyRepository repository;

    @PersistenceContext
    EntityManager em;

    @Nested
    @DisplayName("저장·조회")
    class SaveAndFind {

        @Test
        @DisplayName("DiscountValue VO·기간·대상 매핑까지 그대로 왕복 저장한다")
        void save_round_trips() {
            UUID createdBy = UUID.randomUUID();
            UUID product1 = UUID.randomUUID();
            UUID combination1 = UUID.randomUUID();
            DiscountPolicy saved = repository.save(DiscountPolicy.create("여름 할인", "설명",
                    DiscountValue.of(DiscountType.RATE, 15), 100, T0, T1, createdBy,
                    List.of(product1), List.of(combination1), T0));

            em.flush();
            em.clear();
            DiscountPolicy r = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(r.name()).isEqualTo("여름 할인");
            assertThat(r.description()).isEqualTo("설명");
            assertThat(r.discountValue()
                    .type()).isEqualTo(DiscountType.RATE);
            assertThat(r.discountValue()
                    .value()).isEqualTo(15);
            assertThat(r.priority()).isEqualTo(100);
            assertThat(r.isActive()).isTrue();
            assertThat(r.createdBy()).isEqualTo(createdBy);
            assertThat(r.startedAt()).isEqualTo(T0);
            assertThat(r.endedAt()).isEqualTo(T1);
            assertThat(r.productIds()).containsExactly(product1);
            assertThat(r.combinationIds()).containsExactly(combination1);
            assertThat(r.createdAt()).isNotNull();
            assertThat(r.updatedAt()).isNotNull();
        }

        @Test
        @DisplayName("findAllActive는 비활성 정책을 제외한다")
        void findAllActive_excludes_inactive() {
            DiscountPolicy active = repository.save(activePolicy());
            DiscountPolicy toDisable = repository.save(activePolicy());
            em.flush();
            em.clear();
            // 비활성으로 전환
            repository.save(repository.findById(toDisable.id())
                    .orElseThrow()
                    .toBuilder()
                    .isActive(false)
                    .build());
            em.flush();
            em.clear();

            assertThat(repository.findAllActive()).extracting(DiscountPolicy::id)
                    .contains(active.id())
                    .doesNotContain(toDisable.id());
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("갱신(UPDATE)")
    class Update {

        @Test
        @DisplayName("루트 필드(이름·할인값·우선순위)를 갱신한다")
        void updates_root_fields() {
            DiscountPolicy saved = repository.save(activePolicy());
            em.flush();
            em.clear();

            repository.save(repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .name("변경됨")
                    .priority(500)
                    .discountValue(DiscountValue.of(DiscountType.FIXED_AMOUNT, 3000))
                    .build());
            em.flush();
            em.clear();

            DiscountPolicy r = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(r.name()).isEqualTo("변경됨");
            assertThat(r.priority()).isEqualTo(500);
            assertThat(r.discountValue()
                    .type()).isEqualTo(DiscountType.FIXED_AMOUNT);
            assertThat(r.discountValue()
                    .value()).isEqualTo(3000);
        }
    }

    @Nested
    @DisplayName("자식 교체 회귀(unique 충돌 방지)")
    class ChildReplaceRegression {

        @Test
        @DisplayName("대상 교체 시 동일 (policy_id, product_id)/(policy_id, combination_id) 재삽입에도 unique 충돌 없이 저장된다")
        void replaceTargets_reusingSameMapping_noUniqueViolation() {
            UUID product1 = UUID.randomUUID();
            UUID combination1 = UUID.randomUUID();
            DiscountPolicy saved = repository.save(DiscountPolicy.create(
                    "정책", null, DiscountValue.of(DiscountType.RATE, 10), 0, null, null,
                    UUID.randomUUID(), List.of(product1), List.of(combination1), T0));
            em.flush();
            em.clear();

            UUID product2 = UUID.randomUUID();
            UUID combination2 = UUID.randomUUID();
            DiscountPolicy updated = repository.findById(saved.id())
                    .orElseThrow()
                    .toBuilder()
                    .productIds(List.of(product1, product2))
                    .combinationIds(List.of(combination1, combination2))
                    .build();

            assertThatNoException().isThrownBy(() -> repository.save(updated));
            em.flush();
            em.clear();

            DiscountPolicy after = repository.findById(saved.id())
                    .orElseThrow();
            assertThat(after.productIds()).containsExactlyInAnyOrder(product1, product2);
            assertThat(after.combinationIds()).containsExactlyInAnyOrder(combination1, combination2);
        }
    }

    private DiscountPolicy activePolicy() {
        return DiscountPolicy.create("정책", null, DiscountValue.of(DiscountType.RATE, 10), 0,
                null, null, UUID.randomUUID(), List.of(), List.of(), T0);
    }
}
