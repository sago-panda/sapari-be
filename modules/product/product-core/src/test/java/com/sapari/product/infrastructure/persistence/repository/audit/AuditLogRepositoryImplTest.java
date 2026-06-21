package com.sapari.product.infrastructure.persistence.repository.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.product.domain.model.audit.ActorType;
import com.sapari.product.domain.model.audit.AuditLog;
import com.sapari.product.domain.repository.audit.AuditLogRepository;
import com.sapari.product.support.AbstractRepositoryIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AuditLogRepositoryImpl} 통합 테스트. append-only 기록 + 행위자별 조회, jsonb detail 왕복.
 */
@DisplayName("AuditLogRepositoryImpl 통합 테스트")
class AuditLogRepositoryImplTest extends AbstractRepositoryIntegrationTest {

    @Autowired
    AuditLogRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("jsonb detail 포함 전 필드를 그대로 왕복 저장한다(id 생성)")
    void save_round_trips() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AuditLog saved = repository.save(AuditLog.create(actorId, ActorType.ADMIN, "PRODUCT_APPROVE",
                targetId, "product", "{\"before\":\"PENDING_REVIEW\",\"after\":\"ON_SALE\"}", "127.0.0.1"));
        em.flush();
        em.clear();

        AuditLog r = repository.findById(saved.id())
                .orElseThrow();
        assertThat(r.id()).isNotNull();
        assertThat(r.actorId()).isEqualTo(actorId);
        assertThat(r.actorType()).isEqualTo(ActorType.ADMIN);
        assertThat(r.action()).isEqualTo("PRODUCT_APPROVE");
        assertThat(r.targetId()).isEqualTo(targetId);
        assertThat(r.targetType()).isEqualTo("product");
        assertThat(r.detail()).contains("ON_SALE"); // jsonb는 정규화되므로 부분 비교
        assertThat(r.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(r.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("SYSTEM 행위(actorId=null)·null detail도 저장된다")
    void system_actor_and_null_detail() {
        AuditLog saved = repository.save(AuditLog.create(null, ActorType.SYSTEM, "BATCH_SETTLEMENT",
                null, null, null, null));
        em.flush();
        em.clear();

        AuditLog r = repository.findById(saved.id())
                .orElseThrow();
        assertThat(r.actorId()).isNull();
        assertThat(r.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(r.detail()).isNull();
    }

    @Test
    @DisplayName("findByActorId는 해당 행위자 로그만 반환한다")
    void findByActorId_filters() {
        UUID actorA = UUID.randomUUID();
        repository.save(AuditLog.create(actorA, ActorType.USER, "A1", null, null, null, null));
        repository.save(AuditLog.create(actorA, ActorType.USER, "A2", null, null, null, null));
        repository.save(AuditLog.create(UUID.randomUUID(), ActorType.USER, "B1", null, null, null, null));
        em.flush();
        em.clear();

        assertThat(repository.findByActorId(actorA)).hasSize(2)
                .allSatisfy(a -> assertThat(a.actorId()).isEqualTo(actorA));
        assertThat(repository.findByActorId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 id는 Optional.empty")
    void findById_unknown_empty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
