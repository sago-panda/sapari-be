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

    private LiveRoom readyRtmpRoom() {
        return scheduledRoom()
                .assignRtmpIngress("ing-1", Instant.parse("2026-06-09T02:00:00Z"))
                .arm(Instant.parse("2026-06-09T03:00:00Z"));
    }

    @Test
    @DisplayName("arm — Scheduled 방을 Ready로 전이하고 scheduledAt 보존·updatedAt 갱신")
    void arm_onScheduled() {
        Instant now = Instant.parse("2026-06-09T03:00:00Z");

        LiveRoom room = scheduledRoom().arm(now);

        assertThat(room.status()).isInstanceOf(LiveStatus.Ready.class);
        assertThat(((LiveStatus.Ready) room.status()).scheduledAt())
                .isEqualTo(Instant.parse("2026-06-10T10:00:00Z"));
        assertThat(room.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("arm — Scheduled 아니면 InvalidLiveStateException")
    void arm_rejectsNonScheduled() {
        LiveRoom live = scheduledRoom().startLive(
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1"),
                Instant.parse("2026-06-10T10:00:00Z"));

        assertThatThrownBy(() -> live.arm(Instant.parse("2026-06-10T10:01:00Z")))
                .isInstanceOf(InvalidLiveStateException.class);
    }

    @Test
    @DisplayName("goLiveFromReady — Ready(RTMP) 방을 새 StreamInfo로 Live 전이")
    void goLiveFromReady_onReadyRtmp() {
        Instant now = Instant.parse("2026-06-10T10:00:00Z");

        LiveRoom live = readyRtmpRoom().goLiveFromReady(StreamInfo.of("sfu-1", "eg-1", "https://hls/1"), now);

        assertThat(live.status()).isInstanceOf(LiveStatus.Live.class);
        LiveStatus.Live liveStatus = (LiveStatus.Live) live.status();
        assertThat(liveStatus.startedAt()).isEqualTo(now);
        assertThat(liveStatus.sfuRoomId()).isEqualTo("sfu-1");
        assertThat(live.egressId()).isEqualTo("eg-1");
        assertThat(live.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("goLiveFromReady — Ready가 아니면 InvalidLiveStateException")
    void goLiveFromReady_rejectsNonReady() {
        LiveRoom scheduledRtmp = scheduledRoom().assignRtmpIngress("ing-1", Instant.parse("2026-06-09T02:00:00Z"));

        assertThatThrownBy(() -> scheduledRtmp.goLiveFromReady(
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1"), Instant.parse("2026-06-10T10:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class);
    }

    @Test
    @DisplayName("canGoLiveByRtmp — Ready+RTMP만 true, WebRtc나 Scheduled는 false")
    void canGoLiveByRtmp_onlyReadyRtmp() {
        assertThat(readyRtmpRoom().canGoLiveByRtmp()).isTrue();
        // Ready지만 WebRtc: RTMP 아님
        assertThat(scheduledRoom().arm(Instant.parse("2026-06-09T03:00:00Z")).canGoLiveByRtmp()).isFalse();
        // RTMP지만 아직 Scheduled(시작 대기 아님)
        assertThat(scheduledRoom().assignRtmpIngress("ing-1", Instant.parse("2026-06-09T02:00:00Z"))
                .canGoLiveByRtmp()).isFalse();
    }

    private LiveRoom liveRoom() {
        return scheduledRoom().startLive(
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1"), Instant.parse("2026-06-10T10:00:00Z"));
    }

    @Test
    @DisplayName("expire — Ready 방을 Ended 로 전이하고 updatedAt 갱신")
    void expire_onReady() {
        Instant now = Instant.parse("2026-06-10T11:00:00Z");

        LiveRoom expired = readyRtmpRoom().expire(now);

        assertThat(expired.status()).isInstanceOf(LiveStatus.Ended.class);
        assertThat(expired.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("expire — 방송된 적이 없으므로 Ended.startedAt 은 null, endedAt 만 채운다")
    void expire_leavesStartedAtNull() {
        Instant now = Instant.parse("2026-06-10T11:00:00Z");

        LiveStatus.Ended ended = (LiveStatus.Ended) readyRtmpRoom().expire(now).status();

        assertThat(ended.startedAt()).isNull();
        assertThat(ended.endedAt()).isEqualTo(now);
        assertThat(ended.hlsArchiveUrl()).isNull(); // egress 를 띄운 적이 없어 남길 아카이브도 없다
    }

    @Test
    @DisplayName("expire — RTMP 로 armed 되지 않은 Ready(WebRtc) 방도 만료된다 (송출 방식과 무관)")
    void expire_onReadyWebRtc() {
        LiveRoom readyWebRtc = scheduledRoom().arm(Instant.parse("2026-06-09T03:00:00Z"));

        assertThat(readyWebRtc.expire(Instant.parse("2026-06-10T11:00:00Z")).status())
                .isInstanceOf(LiveStatus.Ended.class);
    }

    @Test
    @DisplayName("expire — Scheduled 는 InvalidLiveStateException (아직 시작조차 누르지 않은 방)")
    void expire_rejectsScheduled() {
        assertThatThrownBy(() -> scheduledRoom().expire(Instant.parse("2026-06-10T11:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class);
    }

    @Test
    @DisplayName("expire — Live 는 InvalidLiveStateException (조회~처리 사이 go-live 된 방을 끊지 않는다)")
    void expire_rejectsLive() {
        assertThatThrownBy(() -> liveRoom().expire(Instant.parse("2026-06-10T11:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class);
    }

    @Test
    @DisplayName("expire — Ended 는 InvalidLiveStateException (중복 실행이 no-op 아닌 예외로 드러난다)")
    void expire_rejectsEnded() {
        LiveRoom ended = liveRoom().endLive(Instant.parse("2026-06-10T11:00:00Z"));

        assertThatThrownBy(() -> ended.expire(Instant.parse("2026-06-10T12:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class);
    }

    @Test
    @DisplayName("canExpire — Ready 만 true")
    void canExpire_onlyReady() {
        assertThat(readyRtmpRoom().canExpire()).isTrue();
        assertThat(scheduledRoom().canExpire()).isFalse();
        assertThat(liveRoom().canExpire()).isFalse();
        assertThat(liveRoom().endLive(Instant.parse("2026-06-10T11:00:00Z")).canExpire()).isFalse();
    }
}
