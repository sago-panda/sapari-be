package com.sapari.product.infrastructure.persistence.repository.audit;

import com.sapari.product.domain.model.audit.AuditLog;
import com.sapari.product.domain.repository.audit.AuditLogRepository;
import com.sapari.product.infrastructure.persistence.mapper.audit.AuditLogMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
/** 감사 로그 영속 어댑터. 기록 후 불변인 append-only(save=INSERT 전용). */
public class AuditLogRepositoryImpl implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;
    private final AuditLogMapper mapper;

    @Override
    public AuditLog save(AuditLog auditLog) {
        // 감사 로그는 append-only.
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(auditLog)));
    }

    @Override
    public Optional<AuditLog> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<AuditLog> findByActorId(UUID actorId) {
        return jpaRepository.findByActorIdOrderByCreatedAtDesc(actorId)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
