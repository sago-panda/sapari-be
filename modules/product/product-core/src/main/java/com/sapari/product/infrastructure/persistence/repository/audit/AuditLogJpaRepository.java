package com.sapari.product.infrastructure.persistence.repository.audit;

import com.sapari.product.infrastructure.persistence.entity.audit.AuditLogEntity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 감사 로그 Spring Data 어댑터. 행위자별 최신순 조회.
 */
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
    List<AuditLogEntity> findByActorIdOrderByCreatedAtDesc(UUID actorId);
}
