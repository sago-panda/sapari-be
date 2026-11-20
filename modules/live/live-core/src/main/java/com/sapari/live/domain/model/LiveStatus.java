package com.sapari.live.domain.model;

import java.time.Instant;

public sealed interface LiveStatus
        permits LiveStatus.Scheduled, LiveStatus.Ready, LiveStatus.Live, LiveStatus.Ended, LiveStatus.Suspended,
                LiveStatus.Cancelled {

    /**
     * 더는 진행되지 않는 방인가(Ended·Cancelled). 나가는 전이가 없으므로 LiveKit 에 남은 방·ingress 는
     * 정당한 것이 없다 — 고아 미디어 재조정이 이 판정으로 유예 없이 회수한다.
     *
     * <p>"방송을 마친 방"과는 다르다. 다시보기처럼 방송이 있었어야 하는 판정은 {@link Ended} 를 직접 본다.
     */
    default boolean isTerminal() {
        return this instanceof Ended || this instanceof Cancelled;
    }

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

    // 판매자가 예약(Scheduled)을 취소한 방. 방송된 적이 없어 채팅 세션도 RoomEnded 도 없다.
    // scheduledAt 은 판매자 목록의 정렬·표시 키라 Scheduled 에서 이어받아 보존한다.
    record Cancelled(Instant scheduledAt, Instant cancelledAt) implements LiveStatus {
        public Cancelled {
            if (scheduledAt == null) throw new IllegalArgumentException("scheduledAt은 필수입니다.");
            if (cancelledAt == null) throw new IllegalArgumentException("cancelledAt은 필수입니다.");
        }
    }
}
