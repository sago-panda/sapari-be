package com.sapari.live.domain.repository;

import java.time.Instant;
import java.util.UUID;

/** {@code live_schema.audit_logs} 에 live 의 관리자 조치를 덧붙인다. */
public interface LiveAuditLogRepository {

    void appendAdminEnd(UUID adminId, UUID roomId, String reason, Instant occurredAt);

    void appendSellerLivePermission(UUID adminId, UUID sellerId, boolean liveEnabled, String reason, Instant occurredAt);
}
