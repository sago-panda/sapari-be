package com.sapari.live.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;

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

    /**
     * RTMP 배정은 도메인 전이가 아니라 조건부 UPDATE 라(RtmpIngressAssigner) 도메인 메서드가 없다.
     * 그 결과 상태를 여기서는 빌더로 직접 만든다.
     */
    private LiveRoom scheduledRtmpRoom(String ingressId) {
        return scheduledRoom().toBuilder()
                .streamType(new LiveStreamType.Rtmp(ingressId))
                .build();
    }

    private LiveRoom readyRtmpRoom() {
        return scheduledRtmpRoom("ing-1").arm(Instant.parse("2026-06-09T03:00:00Z"));
    }

    @Test
    @DisplayName("hasIngress — 방에 배정된 id 만 true, 다른 방 id·null·blank 는 false(모르면 전이 안 함)")
    void hasIngress_matchesOnlyOwnIngress() {
        LiveRoom rtmp = scheduledRtmpRoom("ing-1");

        assertThat(rtmp.hasIngress("ing-1")).isTrue();
        assertThat(rtmp.hasIngress("ing-OTHER")).isFalse();
        assertThat(rtmp.hasIngress(null)).isFalse();
        assertThat(rtmp.hasIngress(" ")).isFalse();
        // WebRtc 방은 배정된 ingress 자체가 없다
        assertThat(scheduledRoom().hasIngress("ing-1")).isFalse();
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
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1", null),
                Instant.parse("2026-06-10T10:00:00Z"));

        assertThatThrownBy(() -> live.arm(Instant.parse("2026-06-10T10:01:00Z")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.INVALID_LIVE_STATE);
    }

    @Test
    @DisplayName("goLiveFromReady — Ready(RTMP) 방을 새 StreamInfo로 Live 전이")
    void goLiveFromReady_onReadyRtmp() {
        Instant now = Instant.parse("2026-06-10T10:00:00Z");

        LiveRoom live = readyRtmpRoom().goLiveFromReady(StreamInfo.of("sfu-1", "eg-1", "https://hls/1", null), now);

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
        LiveRoom scheduledRtmp = scheduledRtmpRoom("ing-1");

        assertThatThrownBy(() -> scheduledRtmp.goLiveFromReady(
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1", null), Instant.parse("2026-06-10T10:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.INVALID_LIVE_STATE);
    }

    @Test
    @DisplayName("canGoLiveByRtmp — Ready+RTMP만 true, WebRtc나 Scheduled는 false")
    void canGoLiveByRtmp_onlyReadyRtmp() {
        assertThat(readyRtmpRoom().canGoLiveByRtmp()).isTrue();
        // Ready지만 WebRtc: RTMP 아님
        assertThat(scheduledRoom().arm(Instant.parse("2026-06-09T03:00:00Z")).canGoLiveByRtmp()).isFalse();
        // RTMP지만 아직 Scheduled(시작 대기 아님)
        assertThat(scheduledRtmpRoom("ing-1").canGoLiveByRtmp()).isFalse();
    }

    private LiveRoom liveRoom() {
        return scheduledRoom().startLive(
                StreamInfo.of("sfu-1", "eg-1", "https://hls/1", null), Instant.parse("2026-06-10T10:00:00Z"));
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
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_END_NOT_ALLOWED);
    }

    @Test
    @DisplayName("expire — Live 는 InvalidLiveStateException (조회~처리 사이 go-live 된 방을 끊지 않는다)")
    void expire_rejectsLive() {
        assertThatThrownBy(() -> liveRoom().expire(Instant.parse("2026-06-10T11:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_END_NOT_ALLOWED);
    }

    @Test
    @DisplayName("expire — Ended 는 InvalidLiveStateException (중복 실행이 no-op 아닌 예외로 드러난다)")
    void expire_rejectsEnded() {
        LiveRoom ended = liveRoom().endLive(Instant.parse("2026-06-10T11:00:00Z"));

        assertThatThrownBy(() -> ended.expire(Instant.parse("2026-06-10T12:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_END_NOT_ALLOWED);
    }

    @Test
    @DisplayName("canExpire — Ready 만 true")
    void canExpire_onlyReady() {
        assertThat(readyRtmpRoom().canExpire()).isTrue();
        assertThat(scheduledRoom().canExpire()).isFalse();
        assertThat(liveRoom().canExpire()).isFalse();
        assertThat(liveRoom().endLive(Instant.parse("2026-06-10T11:00:00Z")).canExpire()).isFalse();
    }

    @Test
    @DisplayName("startLive — Scheduled 가 아니면 시작 불가(LIVE-003)")
    void startLive_rejectsNonScheduled() {
        assertThatThrownBy(() -> liveRoom().startLive(
                StreamInfo.of("sfu-2", "eg-2", "https://hls/2", null), Instant.parse("2026-06-10T10:05:00Z")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.INVALID_LIVE_STATE);
    }

    @Test
    @DisplayName("endLive — 방송 중/정지가 아니면 종료 불가(LIVE-009)")
    void endLive_rejectsScheduled() {
        assertThatThrownBy(() -> scheduledRoom().endLive(Instant.parse("2026-06-10T11:00:00Z")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_END_NOT_ALLOWED);
    }

    // ---- SPR-158: 취소 · 수정 상태 전이 (6개 상태 전부) ----

    private static final Instant SCHEDULED_AT = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-09T05:00:00Z");

    /** 상태마다 방 하나. 빌더로 상태를 직접 심는다 — 전이 경로가 아니라 "그 상태에서의 가드"를 재는 것이다. */
    static Stream<Arguments> everyStatus() {
        return Stream.of(
                Arguments.of("Scheduled", new LiveStatus.Scheduled(SCHEDULED_AT)),
                Arguments.of("Ready", new LiveStatus.Ready(SCHEDULED_AT)),
                Arguments.of("Live", new LiveStatus.Live(SCHEDULED_AT, "sfu-1", "eg-1", "https://hls/1")),
                Arguments.of("Suspended", new LiveStatus.Suspended(SCHEDULED_AT, NOW, "사유")),
                Arguments.of("Ended", new LiveStatus.Ended(SCHEDULED_AT, SCHEDULED_AT.plusSeconds(60), null)),
                Arguments.of("Cancelled", new LiveStatus.Cancelled(SCHEDULED_AT, NOW)));
    }

    private LiveRoom roomIn(LiveStatus status) {
        return scheduledRoom().toBuilder().id(UUID.randomUUID()).status(status).build();
    }

    @Test
    @DisplayName("everyStatus 는 sealed permits 전부를 덮는다 — 상태가 늘면 아래 표가 낡았다는 신호")
    void everyStatusCoversAllPermits() {
        assertThat(everyStatus().map(a -> a.get()[1].getClass()).toArray())
                .containsExactlyInAnyOrder((Object[]) LiveStatus.class.getPermittedSubclasses());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyStatus")
    @DisplayName("cancel — Scheduled 만 Cancelled 로, 나머지는 LIVE-013")
    void cancel_onlyScheduled(String name, LiveStatus status) {
        LiveRoom room = roomIn(status);
        if (status instanceof LiveStatus.Scheduled) {
            LiveRoom cancelled = room.cancel(NOW);
            assertThat(cancelled.status()).isEqualTo(new LiveStatus.Cancelled(SCHEDULED_AT, NOW));
            assertThat(cancelled.updatedAt()).isEqualTo(NOW);
            assertThat(room.canCancel()).isTrue();
        } else {
            assertThat(room.canCancel()).isFalse();
            assertThatThrownBy(() -> room.cancel(NOW))
                    .isInstanceOf(InvalidLiveStateException.class)
                    .extracting(e -> ((LiveDomainException) e).getErrorCode())
                    .isEqualTo(LiveErrorCode.CANCEL_NOT_ALLOWED);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyStatus")
    @DisplayName("update — Scheduled 만 값을 바꾸고, 나머지는 LIVE-014")
    void update_onlyScheduled(String name, LiveStatus status) {
        LiveRoom room = roomIn(status);
        Instant newAt = SCHEDULED_AT.plusSeconds(3600);
        if (status instanceof LiveStatus.Scheduled) {
            LiveRoom updated = room.update("새 제목", "새 설명", "https://thumb/2", newAt, NOW);
            assertThat(updated.title()).isEqualTo("새 제목");
            assertThat(updated.description()).isEqualTo("새 설명");
            assertThat(updated.thumbnailUrl()).isEqualTo("https://thumb/2");
            // 예정 시각은 상태에 담겨야 영속에 반영된다(매퍼 applyScheduledAt)
            assertThat(updated.status()).isEqualTo(new LiveStatus.Scheduled(newAt));
            assertThat(updated.scheduledAt()).isEqualTo(newAt);
            assertThat(room.canUpdate()).isTrue();
        } else {
            assertThat(room.canUpdate()).isFalse();
            assertThatThrownBy(() -> room.update("새 제목", "새 설명", "https://thumb/2", newAt, NOW))
                    .isInstanceOf(InvalidLiveStateException.class)
                    .extracting(e -> ((LiveDomainException) e).getErrorCode())
                    .isEqualTo(LiveErrorCode.UPDATE_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("update — 생성과 같은 필수 검사(제목·예정 시각)")
    void update_requiresSameFieldsAsCreate() {
        assertThatThrownBy(() -> scheduledRoom().update(" ", "설명", "https://t", SCHEDULED_AT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scheduledRoom().update("제목", "설명", "https://t", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Cancelled 방은 어떤 전이 가드도 통과하지 못한다")
    void cancelledRoomPassesNoGuard() {
        LiveRoom room = scheduledRtmpRoom("ing-1").cancel(NOW);

        assertThat(room.canStartLive()).isFalse();
        assertThat(room.canEnterLive()).isFalse();
        assertThat(room.canEndLive()).isFalse();
        assertThat(room.canGoLiveByRtmp()).isFalse();
        assertThat(room.canPrepareIngress()).isFalse();
        assertThat(room.canExpire()).isFalse();
        assertThat(room.status().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("isTerminal — Ended·Cancelled 만")
    void isTerminal_onlyEndedAndCancelled() {
        assertThat(everyStatus().map(a -> (LiveStatus) a.get()[1]).filter(LiveStatus::isTerminal)
                .map(Object::getClass))
                .containsExactlyInAnyOrder(LiveStatus.Ended.class, LiveStatus.Cancelled.class);
    }

    // ---- SPR-156: 예정 시각은 미래만(LIVE-015), 설명은 선택 — create·update 가 같은 규칙 ----

    private static final Instant T0 = Instant.parse("2026-06-09T00:00:00Z");

    @ParameterizedTest
    @MethodSource("nonFutureSchedules")
    @DisplayName("create — 예정 시각이 현재 이하면 LIVE-015")
    void create_rejectsNonFutureSchedule(Instant scheduledAt) {
        assertThatThrownBy(() -> LiveRoom.create(UUID.randomUUID(), "제목", null, "닉네임", "https://thumb", scheduledAt, T0))
                .isInstanceOf(InvalidLiveRoomException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE);
    }

    @ParameterizedTest
    @MethodSource("nonFutureSchedules")
    @DisplayName("update — 예정 시각이 현재 이하면 LIVE-015")
    void update_rejectsNonFutureSchedule(Instant scheduledAt) {
        assertThatThrownBy(() -> scheduledRoom().update("제목", null, "https://thumb", scheduledAt, T0))
                .isInstanceOf(InvalidLiveRoomException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE);
    }

    static Stream<Instant> nonFutureSchedules() {
        return Stream.of(T0, T0.minusMillis(1));
    }

    @Test
    @DisplayName("create·update — 현재+1ms 는 통과")
    void futureByOneMillisPasses() {
        Instant at = T0.plusMillis(1);
        assertThat(LiveRoom.create(UUID.randomUUID(), "제목", null, "닉네임", "https://thumb", at, T0).scheduledAt()).isEqualTo(at);
        assertThat(scheduledRoom().update("제목", null, "https://thumb", at, T0).scheduledAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("create·update — 공백뿐인 설명은 없음(null)으로 저장")
    void blankDescriptionBecomesNull() {
        Instant at = T0.plusSeconds(60);
        assertThat(LiveRoom.create(UUID.randomUUID(), "제목", "  ", "닉네임", "https://thumb", at, T0).description()).isNull();
        assertThat(scheduledRoom().update("제목", " ", "https://thumb", at, T0).description()).isNull();
        assertThat(scheduledRoom().update("제목", "설명", "https://thumb", at, T0).description()).isEqualTo("설명");
    }
}
