package com.sapari.live.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.entity.StreamType;

/**
 * MapStruct 생성 구현체({@code Mappers.getMapper})로 동작을 고정한다.
 * sealed 상태 ↔ enum/컬럼 변환과, 기존 hand-written 매퍼의 미묘한 동작(streamInfo·top-level scheduledAt 비움)을 검증.
 */
class LiveRoomMapperTest {

    private final LiveRoomMapper mapper = Mappers.getMapper(LiveRoomMapper.class);

    @ParameterizedTest
    @ValueSource(strings = {"https://cdn/720p/index.m3u8", "https://cdn/master.m3u8",
            "https://cdn/master.m3u8?v=1", "https://cdn/renamed-live.m3u8"})
    void copiedLiveUrlIsUnavailableUntilBackfilled(String liveUrl) {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        LiveRoomEntity entity = LiveRoomEntity.builder().liveStatus(LiveRoomStatus.ENDED)
                .startedAt(now).endedAt(now.plusSeconds(440)).sfuRoomId("sfu")
                .hlsUrl(liveUrl).hlsArchiveUrl(liveUrl).build();
        LiveRoom legacy = mapper.toDomain(entity);

        assertThat(legacy.streamInfo().hlsArchiveUrl()).isEqualTo(liveUrl);
        assertThat(((LiveStatus.Ended) legacy.status()).hlsArchiveUrl()).isNull();
        mapper.updateEntityFromDomain(entity, legacy);
        assertThat(entity.getHlsArchiveUrl()).isEqualTo(liveUrl); // 조회는 DB 값을 바꾸지 않는다.

        String archive = "https://cdn/replays/renamed-event.m3u8?v=2";
        entity.updateHlsArchiveUrl(archive); // 검증된 아카이브로 백필한 행을 다시 읽는다.
        assertThat(((LiveStatus.Ended) mapper.toDomain(entity).status()).hlsArchiveUrl()).isEqualTo(archive);
        assertThat(entity.getHlsUrl()).isEqualTo(liveUrl);
    }

    @Test
    void legacyArchiveIsFilteredWithoutSfuRoomId() {
        LiveRoomEntity entity = LiveRoomEntity.builder().liveStatus(LiveRoomStatus.ENDED)
                .endedAt(Instant.parse("2026-09-11T00:00:00Z"))
                .hlsUrl("https://cdn/master.m3u8").hlsArchiveUrl("https://cdn/master.m3u8").build();
        assertThat(((LiveStatus.Ended) mapper.toDomain(entity).status()).hlsArchiveUrl()).isNull();
        mapper.updateEntityFromDomain(entity, mapper.toDomain(entity));
        assertThat(entity.getHlsArchiveUrl()).isEqualTo("https://cdn/master.m3u8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIVE", "SUSPENDED", "READY"})
    void legacyArchiveSurvivesTransitionAndRepeatedSave(String state) {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        String liveUrl = "https://cdn/master.m3u8?v=1";
        LiveRoomEntity entity = LiveRoomEntity.builder().liveStatus(LiveRoomStatus.valueOf(state))
                .scheduledAt(now).startedAt(now).suspendedAt(now).suspendedReason("중단")
                .sfuRoomId("sfu").hlsUrl(liveUrl).hlsArchiveUrl(liveUrl).build();
        LiveRoom loaded = mapper.toDomain(entity);
        assertThat(loaded.streamInfo().hlsArchiveUrl()).isEqualTo(liveUrl);
        LiveRoom ended = loaded.canExpire() ? loaded.expire(now.plusSeconds(440)) : loaded.endLive(now.plusSeconds(440));
        mapper.updateEntityFromDomain(entity, ended);
        mapper.updateEntityFromDomain(entity, mapper.toDomain(entity));
        assertThat(entity.getHlsArchiveUrl()).isEqualTo(liveUrl);
        assertThat(entity.getHlsUrl()).isEqualTo(liveUrl);
        assertThat(entity.getLiveStatus()).isEqualTo(LiveRoomStatus.ENDED);
        assertThat(((LiveStatus.Ended) mapper.toDomain(entity).status()).hlsArchiveUrl()).isNull();
    }

    @Test
    void nullArchiveStillClearsNonLegacyValue() {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        LiveRoomEntity entity = LiveRoomEntity.builder().liveStatus(LiveRoomStatus.ENDED)
                .endedAt(now).hlsUrl("https://cdn/index.m3u8")
                .hlsArchiveUrl("https://cdn/playlist.m3u8").build();
        LiveRoom cleared = mapper.toDomain(entity).toBuilder().status(new LiveStatus.Ended(null, now, null)).build();
        mapper.updateEntityFromDomain(entity, cleared);
        assertThat(entity.getHlsArchiveUrl()).isNull();
    }

    @Test
    void archiveSurvivesInsertReloadEndAndUpdate() {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        StreamInfo stream = StreamInfo.of("sfu", "egress", "https://cdn/720p/index.m3u8",
                "https://cdn/720p/playlist.m3u8");
        LiveRoom room = LiveRoom.create(UUID.randomUUID(), "제목", "설명", "닉네임", null, now, now)
                .startLive(stream, now);
        LiveRoomEntity entity = mapper.toEntity(room);
        assertThat(entity.getHlsArchiveUrl()).isEqualTo(stream.hlsArchiveUrl());
        LiveRoom reloaded = mapper.toDomain(entity);
        assertThat(reloaded.streamInfo()).isEqualTo(stream);
        mapper.updateEntityFromDomain(entity, reloaded.endLive(now.plusSeconds(440)));
        LiveStatus.Ended ended = (LiveStatus.Ended) mapper.toDomain(entity).status();
        assertThat(ended.hlsArchiveUrl()).isEqualTo(stream.hlsArchiveUrl());
        assertThat(entity.getHlsUrl()).isEqualTo(stream.hlsUrl());
    }

    @Test
    void archiveSurvivesStartUpdateReloadAndSuspendedEnd() {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        LiveRoom scheduled = LiveRoom.create(UUID.randomUUID(), "제목", "설명", "닉네임", null, now, now);
        LiveRoomEntity entity = mapper.toEntity(scheduled);
        StreamInfo stream = StreamInfo.of("sfu", "egress", "https://cdn/master.m3u8",
                "https://cdn/archive-master.m3u8");
        mapper.updateEntityFromDomain(entity, scheduled.startLive(stream, now));
        LiveRoom suspended = mapper.toDomain(entity).toBuilder()
                .status(new LiveStatus.Suspended(now, now.plusSeconds(100), "중단")).build();
        mapper.updateEntityFromDomain(entity, suspended);
        LiveRoom ended = mapper.toDomain(entity).endLive(now.plusSeconds(440));
        mapper.updateEntityFromDomain(entity, ended);
        assertThat(((LiveStatus.Ended) mapper.toDomain(entity).status()).hlsArchiveUrl())
                .isEqualTo(stream.hlsArchiveUrl());
        assertThat(entity.getHlsUrl()).isEqualTo(stream.hlsUrl());
    }

    @Test
    @DisplayName("toDomain — SCHEDULED: 상태는 Scheduled, top-level scheduledAt 은 비운다")
    void toDomain_scheduled() {
        Instant scheduledAt = Instant.parse("2026-06-10T10:00:00Z");
        UUID sellerId = UUID.randomUUID();
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(sellerId)
                .title("제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(scheduledAt)
                .build();

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.sellerId()).isEqualTo(sellerId);
        assertThat(room.title()).isEqualTo("제목");
        assertThat(room.streamInfo()).isNull(); // sfu_room_id 미배정 → StreamInfo 없음
        assertThat(room.scheduledAt()).isNull(); // 기존 동작 보존: top-level 은 비우고 상태에만 담는다
        assertThat(room.status()).isInstanceOf(LiveStatus.Scheduled.class);
        assertThat(((LiveStatus.Scheduled) room.status()).scheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    @DisplayName("toDomain — LIVE: 상태는 Live, sfu/egress/hls 복원")
    void toDomain_live() {
        Instant startedAt = Instant.parse("2026-06-10T11:00:00Z");
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.LIVE)
                .startedAt(startedAt)
                .sfuRoomId("sfu-1")
                .egressId("eg-1")
                .hlsUrl("https://hls/1")
                .build();

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.status()).isInstanceOf(LiveStatus.Live.class);
        LiveStatus.Live live = (LiveStatus.Live) room.status();
        assertThat(live.startedAt()).isEqualTo(startedAt);
        assertThat(live.sfuRoomId()).isEqualTo("sfu-1");
        assertThat(live.egressId()).isEqualTo("eg-1");
        assertThat(live.hlsUrl()).isEqualTo("https://hls/1");
    }

    @Test
    @DisplayName("toDomain — ENDED: archive 는 hls_archive_url 에서 읽는다 (hls_url 은 방송 중 URL)")
    void toDomain_ended_readsArchiveColumn() {
        // 두 컬럼을 일부러 다른 값으로 둔다 — 값이 같으면 잘못된 컬럼을 읽어도 통과한다.
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.ENDED)
                .startedAt(Instant.parse("2026-06-10T11:00:00Z"))
                .endedAt(Instant.parse("2026-06-10T12:00:00Z"))
                .hlsUrl("https://hls/live-only")
                .hlsArchiveUrl("https://hls/archive")
                .build();

        LiveStatus.Ended ended = (LiveStatus.Ended) mapper.toDomain(entity).status();

        assertThat(ended.hlsArchiveUrl()).isEqualTo("https://hls/archive");
    }

    @Test
    @DisplayName("toDomain — stream 컬럼을 StreamInfo 로 복원한다(sfuRoomId()/egressId() NPE 회귀 방지)")
    void toDomain_restoresStreamInfo() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.LIVE)
                .startedAt(Instant.parse("2026-06-10T11:00:00Z"))
                .sfuRoomId("sfu-1")
                .egressId("eg-1")
                .hlsUrl("https://hls/1")
                .build();

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.streamInfo()).isNotNull();
        assertThat(room.sfuRoomId()).isEqualTo("sfu-1");
        assertThat(room.egressId()).isEqualTo("eg-1");
        assertThat(room.hlsUrl()).isEqualTo("https://hls/1");
    }

    @Test
    @DisplayName("toDomain — Ready 방(sfuRoomId 만 배정): egress/hls 가 비어도 예외 없이 StreamInfo 복원")
    void toDomain_restoresStreamInfo_sfuOnly() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.READY)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .sfuRoomId("sfu-1")
                .build();

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.sfuRoomId()).isEqualTo("sfu-1");
        assertThat(room.egressId()).isNull();
        assertThat(room.hlsUrl()).isNull();
    }

    @Test
    @DisplayName("toDomain — sfu_room_id 미배정(createRoom 전/실패) 행도 예외 없이 읽힌다")
    void toDomain_nullSfuRoomId_isReadable() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .build();

        // StreamInfo 는 sfuRoomId 를 필수 검증한다 — 여기서 던지면 해당 행을 조회조차 못 한다.
        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.streamInfo()).isNull();
    }

    @Test
    @DisplayName("왕복 — Live 방을 toEntity→toDomain 해도 stream 정보가 보존된다")
    void roundTrip_preservesStreamInfo() {
        Instant startedAt = Instant.parse("2026-06-10T11:00:00Z");
        LiveRoom room = LiveRoom.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Live(startedAt, "sfu-1", "eg-1", "https://hls/1"))
                .build();

        LiveRoom restored = mapper.toDomain(mapper.toEntity(room));

        assertThat(restored.streamInfo()).isEqualTo(StreamInfo.of("sfu-1", "eg-1", "https://hls/1", null));
    }

    @Test
    @DisplayName("toEntity — Scheduled: liveStatus=SCHEDULED, scheduledAt 세팅, stream 컬럼은 null")
    void toEntity_scheduled() {
        Instant scheduledAt = Instant.parse("2026-06-10T10:00:00Z");
        LiveRoom room = LiveRoom.create(
                UUID.randomUUID(), "제목", "설명", "닉네임", "https://thumb", scheduledAt,
                Instant.parse("2026-06-09T00:00:00Z"));

        LiveRoomEntity entity = mapper.toEntity(room);

        assertThat(entity.getLiveStatus()).isEqualTo(LiveRoomStatus.SCHEDULED);
        assertThat(entity.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(entity.getTitle()).isEqualTo("제목");
        assertThat(entity.getSfuRoomId()).isNull();
        assertThat(entity.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("toEntity — Live: applyLive 로 startedAt/sfu/egress/hls 와 liveStatus=LIVE 세팅")
    void toEntity_live() {
        Instant startedAt = Instant.parse("2026-06-10T11:00:00Z");
        LiveRoom room = LiveRoom.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Live(startedAt, "sfu-1", "eg-1", "https://hls/1"))
                .build();

        LiveRoomEntity entity = mapper.toEntity(room);

        assertThat(entity.getLiveStatus()).isEqualTo(LiveRoomStatus.LIVE);
        assertThat(entity.getStartedAt()).isEqualTo(startedAt);
        assertThat(entity.getSfuRoomId()).isEqualTo("sfu-1");
        assertThat(entity.getEgressId()).isEqualTo("eg-1");
        assertThat(entity.getHlsUrl()).isEqualTo("https://hls/1");
    }

    @Test
    @DisplayName("updateEntityFromDomain — 평면 필드 갱신 + 상태 전이(SCHEDULED→LIVE) 적용")
    void updateEntityFromDomain_appliesStatusTransition() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("이전 제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .build();

        Instant startedAt = Instant.parse("2026-06-10T11:00:00Z");
        LiveRoom room = LiveRoom.builder()
                .sellerId(entity.getSellerId())
                .title("새 제목")
                .sellerNickname("닉네임")
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .status(new LiveStatus.Live(startedAt, "sfu-1", "eg-1", "https://hls/1"))
                .build();

        mapper.updateEntityFromDomain(entity, room);

        assertThat(entity.getTitle()).isEqualTo("새 제목");
        assertThat(entity.getLiveStatus()).isEqualTo(LiveRoomStatus.LIVE);
        assertThat(entity.getStartedAt()).isEqualTo(startedAt);
        assertThat(entity.getSfuRoomId()).isEqualTo("sfu-1");
    }

    @Test
    @DisplayName("updateEntityFromDomain — DB 에서 읽은 Scheduled 방을 되저장해도 scheduled_at 이 보존된다")
    void updateEntityFromDomain_preservesScheduledAt() {
        Instant scheduledAt = Instant.parse("2026-06-10T10:00:00Z");
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(scheduledAt)
                .build();

        LiveRoom loaded = mapper.toDomain(entity);
        mapper.updateEntityFromDomain(entity, loaded);

        assertThat(entity.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    @DisplayName("toDomain — 기본 WEBRTC: streamType 을 WebRtc 로 복원")
    void toDomain_webrtc() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .build();

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.streamType()).isInstanceOf(LiveStreamType.WebRtc.class);
    }

    @Test
    @DisplayName("toDomain — RTMP: streamType 을 Rtmp(ingressId) 로 복원")
    void toDomain_rtmp() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .build();
        entity.assignRtmpIngress("ing-1");

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.streamType()).isInstanceOf(LiveStreamType.Rtmp.class);
        assertThat(((LiveStreamType.Rtmp) room.streamType()).ingressId()).isEqualTo("ing-1");
    }

    @Test
    @DisplayName("toDomain — READY: 상태는 Ready, scheduledAt 복원")
    void toDomain_ready() {
        Instant scheduledAt = Instant.parse("2026-06-10T10:00:00Z");
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.READY)
                .scheduledAt(scheduledAt)
                .build();

        LiveRoom room = mapper.toDomain(entity);

        assertThat(room.status()).isInstanceOf(LiveStatus.Ready.class);
        assertThat(((LiveStatus.Ready) room.status()).scheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    @DisplayName("updateEntityFromDomain — Scheduled→Ready 전이 시 status=READY, scheduled_at 보존")
    void updateEntityFromDomain_appliesReadyTransition() {
        Instant scheduledAt = Instant.parse("2026-06-10T10:00:00Z");
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(scheduledAt)
                .build();

        LiveRoom ready = mapper.toDomain(entity)
                .arm(Instant.parse("2026-06-09T03:00:00Z"));

        mapper.updateEntityFromDomain(entity, ready);

        assertThat(entity.getLiveStatus()).isEqualTo(LiveRoomStatus.READY);
        assertThat(entity.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    @DisplayName("toEntity — create()는 WEBRTC: streamType=WEBRTC, ingressId=null")
    void toEntity_defaultsWebRtc() {
        LiveRoom room = LiveRoom.create(
                UUID.randomUUID(), "제목", "설명", "닉네임", "https://thumb",
                Instant.parse("2026-06-10T10:00:00Z"), Instant.parse("2026-06-09T00:00:00Z"));

        LiveRoomEntity entity = mapper.toEntity(room);

        assertThat(entity.getStreamType()).isEqualTo(StreamType.WEBRTC);
        assertThat(entity.getIngressId()).isNull();
    }

    @Test
    @DisplayName("updateEntityFromDomain — RTMP 배정: streamType=RTMP, ingressId 세팅")
    void updateEntityFromDomain_assignsRtmp() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .build();

        LiveRoom room = LiveRoom.create(
                        entity.getSellerId(), "제목", "설명", "닉네임", "https://thumb",
                        Instant.parse("2026-06-10T10:00:00Z"), Instant.parse("2026-06-09T00:00:00Z"))
                .toBuilder().streamType(new LiveStreamType.Rtmp("ing-9")).build();

        mapper.updateEntityFromDomain(entity, room);

        assertThat(entity.getStreamType()).isEqualTo(StreamType.RTMP);
        assertThat(entity.getIngressId()).isEqualTo("ing-9");
    }

    @Test
    @DisplayName("updateEntityFromDomain — WebRtc 방: RTMP 였던 엔티티의 streamType=WEBRTC, ingressId clear")
    void updateEntityFromDomain_clearsIngressOnWebRtc() {
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID())
                .title("제목")
                .liveStatus(LiveRoomStatus.SCHEDULED)
                .scheduledAt(Instant.parse("2026-06-10T10:00:00Z"))
                .build();
        entity.assignRtmpIngress("ing-old"); // 기존 RTMP 상태

        LiveRoom webRtcRoom = LiveRoom.create(
                entity.getSellerId(), "제목", "설명", "닉네임", "https://thumb",
                Instant.parse("2026-06-10T10:00:00Z"), Instant.parse("2026-06-09T00:00:00Z"));

        mapper.updateEntityFromDomain(entity, webRtcRoom);

        assertThat(entity.getStreamType()).isEqualTo(StreamType.WEBRTC);
        assertThat(entity.getIngressId()).isNull();
    }
}
