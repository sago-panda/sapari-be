package com.sapari.live.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.entity.StreamType;

/**
 * MapStruct 생성 구현체({@code Mappers.getMapper})로 동작을 고정한다.
 * sealed 상태 ↔ enum/컬럼 변환과, 기존 hand-written 매퍼의 미묘한 동작(streamInfo·top-level scheduledAt 비움)을 검증.
 */
class LiveRoomMapperTest {

    private final LiveRoomMapper mapper = Mappers.getMapper(LiveRoomMapper.class);

    @Test
    @DisplayName("toDomain — SCHEDULED: 상태는 Scheduled, streamInfo·top-level scheduledAt 은 비운다")
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
        assertThat(room.streamInfo()).isNull();
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
                .assignRtmpIngress("ing-9", Instant.parse("2026-06-09T01:00:00Z"));

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
