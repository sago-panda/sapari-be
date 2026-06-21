package com.sapari.product.infrastructure.persistence.entity.audit;

import com.sapari.product.domain.model.audit.ActorType;

import com.sapari.storage.db.entity.BaseUuidEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 주요 행위 감사 로그. 행위자·대상 리소스·IP 기록.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "audit_logs", schema = "product_schema")
public class AuditLogEntity extends BaseUuidEntity {

    // 행위자 ID (users.id — role로 구분). SYSTEM이면 NULL. 물리 FK 미사용.
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    private ActorType actorType;

    // 행위 코드 (예: PAYMENT_COMPLETE)
    private String action;

    private UUID targetId;

    private String targetType;

    // 변경 전후 데이터 등 상세 jsonb
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    private String ipAddress;

    /**
     * 빌더 전용 생성자. JPA가 요구하는 protected 기본 생성자와 구분하여 private으로 두고, MapStruct 매퍼·테스트 픽스처가 빌더로만 신규 생성/재구성하도록 강제한다.
     *
     * <p>actorType이 SYSTEM인 경우 actorId는 null일 수 있다.
     */
    @Builder
    private AuditLogEntity(UUID actorId, ActorType actorType, String action, UUID targetId,
                           String targetType, String detail, String ipAddress) {
        this.actorId = actorId;
        this.actorType = actorType;
        this.action = action;
        this.targetId = targetId;
        this.targetType = targetType;
        this.detail = detail;
        this.ipAddress = ipAddress;
    }
}
