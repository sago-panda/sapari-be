package com.sapari.live.domain.model;

import java.time.Instant;

public sealed interface LiveStatus
        permits LiveStatus.Scheduled, LiveStatus.Ready, LiveStatus.Live, LiveStatus.Ended, LiveStatus.Suspended {

    record Scheduled(Instant scheduledAt) implements LiveStatus {
        public Scheduled {
            if (scheduledAt == null) throw new IllegalArgumentException("scheduledAt은 필수입니다.");
        }
    }

    // RTMP 방송 시작 대기: 판매자가 방송 시작(상품 등록)을 눌렀으나 OBS가 아직 ingress에 연결되지 않은 상태.
    // ingress_started webhook(또는 시작 시점의 ingress 활성 확인)이 도착하면 Live로 전이한다.
    // scheduledAt은 Scheduled에서 이어받아 보존한다(예약 알림·통계가 참조).
    record Ready(Instant scheduledAt) implements LiveStatus {
        public Ready {
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
