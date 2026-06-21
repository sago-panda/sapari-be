package com.sapari.product.domain.repository.audit;

import com.sapari.product.domain.model.audit.AuditLog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 감사 로그 영속 포트. 기록 후 불변인 append-only라 {@code save}는 INSERT 전용이다.
 */
public interface AuditLogRepository {
    /**
     * 감사 로그 1건 기록(append-only).
     */
    AuditLog save(AuditLog auditLog);

    Optional<AuditLog> findById(UUID id);

    /**
     * 특정 행위자의 감사 로그 목록(최신순).
     */
    List<AuditLog> findByActorId(UUID actorId);
}
