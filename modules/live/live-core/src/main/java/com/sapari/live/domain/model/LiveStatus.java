package com.sapari.live.domain.model;

import java.time.Instant;

public sealed interface LiveStatus
        permits LiveStatus.Scheduled, LiveStatus.Live, LiveStatus.Ended, LiveStatus.Suspended {

    record Scheduled(Instant scheduledAt) implements LiveStatus {
        public Scheduled {
            if (scheduledAt == null) throw new IllegalArgumentException("scheduledAt은 필수입니다.");
        }
    }

    record Live(Instant startedAt, String sfuRoomId, String egressId, String hlsUrl) implements LiveStatus {
        public Live {
            if (startedAt == null) throw new IllegalArgumentException("startedAt은 필수입니다.");
            if (sfuRoomId == null || sfuRoomId.isBlank()) throw new IllegalArgumentException("sfuRoomId는 필수입니다.");
        }
    }

    record Ended(Instant startedAt, Instant endedAt, String hlsArchiveUrl) implements LiveStatus {
        public Ended {
            if (endedAt == null) throw new IllegalArgumentException("endedAt은 필수입니다.");
            if (startedAt != null && endedAt.isBefore(startedAt)) throw new IllegalArgumentException("endedAt은 startedAt 이후여야 합니다.");
        }
    }

    // startedAt: 방송 시작 후 정지된 경우 Live.startedAt 전달, 시작 전 정지된 경우 null
    record Suspended(Instant startedAt, Instant suspendedAt, String reason) implements LiveStatus {
        public Suspended {
            if (suspendedAt == null) throw new IllegalArgumentException("suspendedAt은 필수입니다.");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("정지 사유는 필수입니다.");
        }
    }
}
