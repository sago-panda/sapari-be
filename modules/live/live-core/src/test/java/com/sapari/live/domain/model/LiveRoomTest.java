package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.domain.exception.InvalidLiveStateException;

class LiveRoomTest {

    private LiveRoom scheduledRoom() {
        return LiveRoom.create(
                UUID.randomUUID(), "제목", "설명", "닉네임", "https://thumb",
                Instant.parse("2026-06-10T10:00:00Z"), Instant.parse("2026-06-09T00:00:00Z"));
    }

    @Test
    @DisplayName("create — 기본 송출은 WebRtc")
    void create_defaultsWebRtc() {
        assertThat(scheduledRoom().streamType()).isInstanceOf(LiveStreamType.WebRtc.class);
    }

    @Test
    @DisplayName("assignRtmpIngress — Scheduled 방을 Rtmp(ingressId)로 전환하고 updatedAt 갱신")
    void assignRtmpIngress_onScheduled() {
        Instant now = Instant.parse("2026-06-09T02:00:00Z");

        LiveRoom room = scheduledRoom().assignRtmpIngress("ing-1", now);

        assertThat(room.streamType()).isInstanceOf(LiveStreamType.Rtmp.class);
        assertThat(((LiveStreamType.Rtmp) room.streamType()).ingressId()).isEqualTo("ing-1");
        assertThat(room.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("assignRtmpIngress — ingressId 가 blank 면 IllegalArgumentException")
    void assignRtmpIngress_rejectsBlankIngressId() {
        Instant now = Instant.parse("2026-06-09T02:00:00Z");

        assertThatThrownBy(() -> scheduledRoom().assignRtmpIngress(" ", now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("assignRtmpIngress — Scheduled 아니면 InvalidLiveStateException")
    void assignRtmpIngress_rejectsNonScheduled() {
        LiveRoom live = scheduledRoom().startLive(
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1"),
                Instant.parse("2026-06-10T10:00:00Z"));

        assertThatThrownBy(() -> live.assignRtmpIngress("ing-1", Instant.parse("2026-06-10T10:01:00Z")))
                .isInstanceOf(InvalidLiveStateException.class);
    }
}
