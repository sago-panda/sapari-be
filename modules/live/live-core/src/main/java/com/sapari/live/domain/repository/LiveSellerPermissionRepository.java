package com.sapari.live.domain.repository;

import java.time.Instant;
import java.util.UUID;

/** {@code live_schema.live_seller_permission} — 판매자별 라이브 권한. 행이 없으면 허용이다. */
public interface LiveSellerPermissionRepository {

    boolean isLiveEnabled(UUID sellerId);

    void save(UUID sellerId, boolean liveEnabled, Instant updatedAt);
}
