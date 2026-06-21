package com.sapari.product.infrastructure.persistence.repository.discount;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.discount.CombinationWinningDiscount;
import com.sapari.product.domain.repository.discount.CombinationWinningDiscountRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link CombinationWinningDiscountRepositoryImpl} 통합 테스트.
 *
 * <p>PK(combinationId)가 앱 주입인 1:1 파생 캐시 — 일반 upsert와 달리 findById 존재 여부로 갱신/신규를 가른다.
 * 같은 combinationId로 다시 save하면 INSERT 실패가 아니라 갱신되어야 한다.
 */
@DisplayName("CombinationWinningDiscountRepositoryImpl 통합 테스트")
class CombinationWinningDiscountRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-02T00:00:00Z");

    @Autowired
    CombinationWinningDiscountRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("승자 할인을 그대로 왕복 저장한다")
    void save_round_trip() {
        UUID combinationId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        repository.save(CombinationWinningDiscount.create(combinationId, policyId, 1_000, 9_000, T0));
        em.flush();
        em.clear();

        CombinationWinningDiscount r = repository.findById(combinationId)
                .orElseThrow();
        assertThat(r.combinationId()).isEqualTo(combinationId);
        assertThat(r.policyId()).isEqualTo(policyId);
        assertThat(r.discountAmount()).isEqualTo(1_000);
        assertThat(r.finalPrice()).isEqualTo(9_000);
        assertThat(r.resolvedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("같은 combinationId로 다시 save하면 INSERT 실패 없이 갱신된다(승자 재해소)")
    void save_twice_same_combination_updates() {
        UUID combinationId = UUID.randomUUID();
        UUID policy1 = UUID.randomUUID();
        UUID policy2 = UUID.randomUUID();
        repository.save(CombinationWinningDiscount.create(combinationId, policy1, 1_000, 9_000, T0));
        em.flush();
        em.clear();

        // 다른 정책이 승자가 됨 — 같은 combinationId로 재저장
        repository.save(CombinationWinningDiscount.create(combinationId, policy2, 2_000, 8_000, T1));
        em.flush();
        em.clear();

        CombinationWinningDiscount r = repository.findById(combinationId)
                .orElseThrow();
        assertThat(r.policyId()).isEqualTo(policy2);
        assertThat(r.discountAmount()).isEqualTo(2_000);
        assertThat(r.finalPrice()).isEqualTo(8_000);
        // 행이 늘지 않고 교체됨
        assertThat(repository.findByPolicyId(policy1)).isEmpty();
        assertThat(repository.findByPolicyId(policy2)).hasSize(1);
    }

    @Test
    @DisplayName("findByPolicyId는 해당 정책이 승자인 조합만 반환한다")
    void findByPolicyId_filters() {
        UUID policy = UUID.randomUUID();
        repository.save(CombinationWinningDiscount.create(UUID.randomUUID(), policy, 100, 900, T0));
        repository.save(CombinationWinningDiscount.create(UUID.randomUUID(), policy, 200, 800, T0));
        repository.save(CombinationWinningDiscount.create(UUID.randomUUID(), UUID.randomUUID(), 300, 700, T0));
        em.flush();
        em.clear();

        assertThat(repository.findByPolicyId(policy)).hasSize(2)
                .allSatisfy(w -> assertThat(w.policyId()).isEqualTo(policy));
        assertThat(repository.findByPolicyId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 combinationId는 Optional.empty(=할인 없음)")
    void findById_unknown_empty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
