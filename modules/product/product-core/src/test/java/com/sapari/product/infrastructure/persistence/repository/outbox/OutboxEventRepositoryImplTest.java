package com.sapari.product.infrastructure.persistence.repository.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.outbox.OutboxEvent;
import com.sapari.product.domain.repository.outbox.OutboxEventRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link OutboxEventRepositoryImpl} 통합 테스트. 앱 공급 id upsert(createdAt 보존) + 처리/실패 전이, 미처리 폴링.
 */
@DisplayName("OutboxEventRepositoryImpl 통합 테스트")
class OutboxEventRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PROCESSED = Instant.parse("2026-01-01T00:05:00Z");

    @Autowired
    OutboxEventRepository repository;

    @PersistenceContext
    EntityManager em;

    private OutboxEvent newEvent(long id) {
        return OutboxEvent.create(id, "product", UUID.randomUUID(), "UPSERTED",
                "{\"name\":\"상품\"}", CREATED);
    }

    @Nested
    @DisplayName("저장·조회")
    class SaveAndFind {

        @Test
        @DisplayName("전 필드를 그대로 왕복 저장한다(retryCount 0, 미처리)")
        void save_round_trips() {
            UUID aggregateId = UUID.randomUUID();
            repository.save(OutboxEvent.create(1L, "product", aggregateId, "UPSERTED",
                    "{\"name\":\"상품\"}", CREATED));
            em.flush();
            em.clear();

            OutboxEvent r = repository.findById(1L)
                    .orElseThrow();
            assertThat(r.aggregateType()).isEqualTo("product");
            assertThat(r.aggregateId()).isEqualTo(aggregateId);
            assertThat(r.eventType()).isEqualTo("UPSERTED");
            assertThat(r.payload()).contains("상품"); // jsonb 정규화 → 부분 비교
            assertThat(r.createdAt()).isEqualTo(CREATED);
            assertThat(r.retryCount()).isZero();
            assertThat(r.processedAt()).isNull();
            assertThat(r.isProcessed()).isFalse();
        }

        @Test
        @DisplayName("존재하지 않는 id는 Optional.empty")
        void findById_unknown_empty() {
            assertThat(repository.findById(9_999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("처리 상태 전이")
    class Transitions {

        @Test
        @DisplayName("markProcessed는 processedAt을 채우고 createdAt은 보존한다")
        void markProcessed_keeps_createdAt() {
            repository.save(newEvent(1L));
            em.flush();
            em.clear();

            repository.save(repository.findById(1L)
                    .orElseThrow()
                    .markProcessed(PROCESSED));
            em.flush();
            em.clear();

            OutboxEvent r = repository.findById(1L)
                    .orElseThrow();
            assertThat(r.isProcessed()).isTrue();
            assertThat(r.processedAt()).isEqualTo(PROCESSED);
            assertThat(r.createdAt()).isEqualTo(CREATED); // 보존
        }

        @Test
        @DisplayName("markFailed는 retryCount를 증가시키고 lastError를 기록한다")
        void markFailed_increments_retry() {
            repository.save(newEvent(1L));
            em.flush();
            em.clear();

            repository.save(repository.findById(1L)
                    .orElseThrow()
                    .markFailed("ES timeout"));
            em.flush();
            em.clear();

            OutboxEvent r = repository.findById(1L)
                    .orElseThrow();
            assertThat(r.retryCount()).isEqualTo(1);
            assertThat(r.lastError()).isEqualTo("ES timeout");
            assertThat(r.isProcessed()).isFalse();
        }
    }

    @Nested
    @DisplayName("미처리 폴링")
    class Unprocessed {

        @Test
        @DisplayName("findUnprocessed는 미처리 이벤트만 반환한다(처리 완료 제외)")
        void findUnprocessed_excludes_processed() {
            repository.save(newEvent(1L)); // 미처리
            repository.save(newEvent(2L)); // 미처리 → 처리완료로 전환
            em.flush();
            em.clear();
            repository.save(repository.findById(2L)
                    .orElseThrow()
                    .markProcessed(PROCESSED));
            em.flush();
            em.clear();

            assertThat(repository.findUnprocessed()).extracting(OutboxEvent::id)
                    .contains(1L)
                    .doesNotContain(2L);
        }
    }
}
