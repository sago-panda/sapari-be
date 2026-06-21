package com.sapari.product.infrastructure.persistence.repository.outbox;

import com.sapari.product.infrastructure.persistence.entity.outbox.OutboxEventEntity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Outbox Spring Data 어댑터(bigint app-TSID). 미처리(processed_at IS NULL) 폴링 큐 조회.
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {
    List<OutboxEventEntity> findByProcessedAtIsNullOrderByCreatedAt();
}
