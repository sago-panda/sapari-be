package com.sapari.product.infrastructure.persistence.repository.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.model.stock.StockReservation;
import com.sapari.product.domain.model.stock.StockReservationStatus;
import com.sapari.product.domain.repository.stock.StockReservationRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;

/**
 * {@link StockReservationRepositoryImpl} 통합 테스트. 단일 애그리거트 upsert + 상태 머신(HELD→COMMITTED/RELEASED/EXPIRED).
 */
@DisplayName("StockReservationRepositoryImpl 통합 테스트")
class StockReservationRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-02T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-12-31T00:00:00Z");

    @Autowired
    StockReservationRepository repository;

    @PersistenceContext
    EntityManager em;

    private StockReservation reload(UUID id) {
        em.flush();
        em.clear();
        return repository.findById(id)
                .orElseThrow();
    }

    @Nested
    @DisplayName("저장·조회")
    class SaveAndFind {

        @Test
        @DisplayName("HELD 상태로 생성해 전 필드를 그대로 왕복 저장한다")
        void save_round_trips_held() {
            UUID combinationId = UUID.randomUUID();
            UUID reservedBy = UUID.randomUUID();
            StockReservation saved = repository.save(
                    StockReservation.create(combinationId, reservedBy, "sess-1", 3, EXPIRES, T0));

            StockReservation r = reload(saved.id());
            assertThat(r.id()).isNotNull();
            assertThat(r.combinationId()).isEqualTo(combinationId);
            assertThat(r.reservedBy()).isEqualTo(reservedBy);
            assertThat(r.sessionId()).isEqualTo("sess-1");
            assertThat(r.quantity()).isEqualTo(3);
            assertThat(r.status()).isEqualTo(StockReservationStatus.HELD);
            assertThat(r.expiresAt()).isEqualTo(EXPIRES);
            assertThat(r.orderItemId()).isNull();
            assertThat(r.createdAt()).isNotNull();
            assertThat(r.updatedAt()).isNotNull();
        }

        @Test
        @DisplayName("findByCombinationIdAndStatus는 조합+상태로 필터링한다")
        void findByCombinationIdAndStatus_filters() {
            UUID combinationId = UUID.randomUUID();
            repository.save(StockReservation.create(combinationId, null, null, 1, EXPIRES, T0));
            em.flush();
            em.clear();

            assertThat(repository.findByCombinationIdAndStatus(combinationId, StockReservationStatus.HELD))
                    .hasSize(1);
            assertThat(repository.findByCombinationIdAndStatus(combinationId, StockReservationStatus.COMMITTED))
                    .isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transitions {

        @Test
        @DisplayName("commit → COMMITTED + orderItemId 저장")
        void commit_persists_committed() {
            StockReservation saved = repository.save(
                    StockReservation.create(UUID.randomUUID(), null, null, 2, EXPIRES, T0));
            UUID orderItemId = UUID.randomUUID();
            repository.save(reload(saved.id()).commit(orderItemId, T1));

            StockReservation r = reload(saved.id());
            assertThat(r.status()).isEqualTo(StockReservationStatus.COMMITTED);
            assertThat(r.orderItemId()).isEqualTo(orderItemId);
        }

        @Test
        @DisplayName("release → RELEASED 저장")
        void release_persists_released() {
            StockReservation saved = repository.save(
                    StockReservation.create(UUID.randomUUID(), null, null, 2, EXPIRES, T0));
            repository.save(reload(saved.id()).release(T1));

            assertThat(reload(saved.id()).status()).isEqualTo(StockReservationStatus.RELEASED);
        }

        @Test
        @DisplayName("expire → EXPIRED 저장")
        void expire_persists_expired() {
            StockReservation saved = repository.save(
                    StockReservation.create(UUID.randomUUID(), null, null, 2, EXPIRES, T0));
            repository.save(reload(saved.id()).expire(T1));

            assertThat(reload(saved.id()).status()).isEqualTo(StockReservationStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCases {

        @Test
        @DisplayName("존재하지 않는 id로 갱신 시 예외(@Repository 예외 변환)")
        void update_nonexistent_throws() {
            StockReservation ghost = StockReservation.create(UUID.randomUUID(), null, null, 1, EXPIRES, T0)
                    .toBuilder()
                    .id(UUID.randomUUID())
                    .build();

            assertThatThrownBy(() -> repository.save(ghost))
                    .isInstanceOf(JpaObjectRetrievalFailureException.class)
                    .hasCauseInstanceOf(EntityNotFoundException.class);
        }
    }
}
