package com.sapari.product.domain.model.audit;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * 감사 로그 애그리거트 루트 (append-only).
 */
@Builder(toBuilder = true)
public record AuditLog(
        UUID id,
        UUID actorId,
        ActorType actorType,
        String action,
        UUID targetId,
        String targetType,
        String detail,
        String ipAddress,
        Instant createdAt
) {

    /**
     * actorType·action 필수. 행위 주체와 무슨 일을 했는지는 감사 로그의 최소 식별 정보다.
     */
    public AuditLog {
        if (actorType == null) {
            throw new IllegalArgumentException("actorType은 필수입니다.");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action은 필수입니다.");
        }
    }

    /**
     * 감사 로그 한 건을 생성한다(append-only). SYSTEM 행위의 경우 actorId는 null일 수 있다. id·생성 시각은 영속 시점에 채워진다.
     */
    public static AuditLog create(UUID actorId, ActorType actorType, String action, UUID targetId,
                                  String targetType, String detail, String ipAddress) {

        return AuditLog.builder()
                .actorId(actorId)
                .actorType(actorType)
                .action(action)
                .targetId(targetId)
                .targetType(targetType)
                .detail(detail)
                .ipAddress(ipAddress)
                .build();
    }
}
