package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.ExpireOrphanLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * OBS 미연결로 Ready 에 갇힌 방의 만료 정리.
 *
 * <p>{@code EndLiveService} 와 달리 소유권 인자가 없고(시스템 정리), 전이가 미디어 호출보다 먼저다.
 * 조회는 반드시 행 잠금({@code findByIdForUpdate}) 이어야 배치 ↔ webhook go-live 가 직렬화된다.
 */
@ExtendWith(MockitoExtension.class)
class ExpireOrphanLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private LiveMetrics liveMetrics;

    @InjectMocks
    private ExpireOrphanLiveService expireOrphanLiveService;

    private FixtureMonkey fixtureMonkey;
    private UUID roomId;
    private Instant now;

    @BeforeEach
    void setup() {
        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();
        roomId = UUID.randomUUID();
        now = Instant.parse("2026-06-10T11:00:00Z");

        // 미디어 정리가 afterCommit 으로 미뤄졌다 — 동기화가 없으면 "트랜잭션 밖" 폴백으로 빠져
        // 즉시 실행되므로, 실제 경로를 밟으려면 트랜잭션 안을 흉내내야 한다.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 실제 커밋에서만 도는 훅을 테스트가 직접 발화시킨다. */
    private void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    /** Ready 방: 예약 시 createRoom 으로 sfuRoomId 만 배정되고 egress 는 아직 없다. */
    private LiveRoom readyRoom(LiveStreamType streamType) {
        return fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("status", new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")))
                .set("streamInfo", StreamInfo.ofSfuRoomId("sfu-1"))
                .set("streamType", streamType)
                .sample();
    }

    @Test
    @DisplayName("RTMP 방 만료: egress 중단 → ingress 삭제 → 방 삭제 순으로 정리하고 Ended 로 저장한다")
    void expire_rtmp_cleansUpInOrder() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(readyRoom(new LiveStreamType.Rtmp("ing-1"))));
        given(timeProvider.now()).willReturn(now);

        expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId));

        triggerAfterCommit();
        // ingress 가 남아 있으면 OBS 자동 재접속이 닫힌 SFU 방을 재생성한다 — closeRoom 이 반드시 마지막
        var order = inOrder(liveMediaManager);
        order.verify(liveMediaManager).stopHlsEgress(roomId);
        order.verify(liveMediaManager).deleteIngress(roomId);
        order.verify(liveMediaManager).closeRoom("sfu-1");

        ArgumentCaptor<LiveRoom> captor = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should(times(1)).save(captor.capture());
        assertThat(captor.getValue().status()).isInstanceOf(LiveStatus.Ended.class);
        assertThat(captor.getValue().updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("WebRTC 방 만료: ingress 삭제는 호출하지 않는다")
    void expire_webrtc_doesNotDeleteIngress() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(readyRoom(new LiveStreamType.WebRtc())));
        given(timeProvider.now()).willReturn(now);

        expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId));

        triggerAfterCommit();
        then(liveMediaManager).should(never()).deleteIngress(roomId);
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
        then(liveMediaManager).should(times(1)).closeRoom("sfu-1");
    }

    @Test
    @DisplayName("egress 중단은 DB 에 egress 기록이 없어도 호출한다 (시작 중 크래시로 egress 만 남은 방)")
    void expire_stopsEgressEvenWithoutEgressId() {
        LiveRoom room = readyRoom(new LiveStreamType.WebRtc());
        assertThat(room.egressId()).isNull(); // Ready 방은 egressId 가 없는 게 정상
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(room));
        given(timeProvider.now()).willReturn(now);

        expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId));

        triggerAfterCommit();
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    @Test
    @DisplayName("createRoom 실패로 SFU 방이 없는 방: closeRoom 을 건너뛴다 (sfuRoomId() 는 NPE)")
    void expire_withoutSfuRoom_skipsCloseRoom() {
        // FixtureMonkey 대신 빌더 — setNull 이어도 StreamInfo 를 한 번 생성해보므로,
        // 랜덤 sfuRoomId 가 공백이면 컴팩트 생성자에서 터진다(플래키).
        LiveRoom room = LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")))
                .streamType(new LiveStreamType.Rtmp("ing-1"))
                .build();
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(room));
        given(timeProvider.now()).willReturn(now);

        expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId));

        triggerAfterCommit();
        then(liveMediaManager).should(never()).closeRoom(org.mockito.ArgumentMatchers.anyString());
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
        then(liveMediaManager).should(times(1)).deleteIngress(roomId);
        then(liveRoomRepository).should(times(1)).save(org.mockito.ArgumentMatchers.any(LiveRoom.class));
    }

    @Test
    @DisplayName("미디어 정리는 커밋 이후에 한다 — 커밋 전엔 LiveKit 을 호출하지 않는다(행 잠금 밖으로 뺀 이유)")
    void expire_mediaCleanupDeferredUntilAfterCommit() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(readyRoom(new LiveStreamType.Rtmp("ing-1"))));
        given(timeProvider.now()).willReturn(now);

        expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId));

        then(liveRoomRepository).should(times(1)).save(org.mockito.ArgumentMatchers.any(LiveRoom.class));
        then(liveMediaManager).shouldHaveNoInteractions();

        triggerAfterCommit();
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    @Test
    @DisplayName("조회는 행 잠금으로 한다 — 잠금 없는 findById 를 쓰면 배치 ↔ go-live 가 직렬화되지 않는다")
    void expire_readsWithRowLock() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(readyRoom(new LiveStreamType.WebRtc())));
        given(timeProvider.now()).willReturn(now);

        expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId));

        then(liveRoomRepository).should(times(1)).findByIdForUpdate(roomId);
        then(liveRoomRepository).should(never()).findById(roomId);
    }

    @Test
    @DisplayName("방이 없으면 LiveNotFoundException — 미디어 정리도 저장도 하지 않는다")
    void expire_roomNotFound() {
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId)))
                .isInstanceOf(LiveNotFoundException.class);

        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(never()).save(org.mockito.ArgumentMatchers.any(LiveRoom.class));
    }

    @Test
    @DisplayName("조회~잠금 사이에 go-live 된 방은 InvalidLiveStateException — 살아 있는 방송의 미디어를 끊지 않는다")
    void expire_alreadyLive_throwsBeforeAnyMediaCall() {
        LiveRoom live = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("status", new LiveStatus.Live(now, "sfu-1", "eg-1", "https://hls/1"))
                .set("streamInfo", StreamInfo.of("sfu-1", "eg-1", "https://hls/1"))
                .set("streamType", new LiveStreamType.Rtmp("ing-1"))
                .sample();
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(live));
        given(timeProvider.now()).willReturn(now);

        assertThatThrownBy(() -> expireOrphanLiveService.expire(new ExpireOrphanLiveCommand(roomId)))
                .isInstanceOf(InvalidLiveStateException.class);

        // expire() 가 미디어 호출보다 먼저라 가드가 걸리면 LiveKit 에 아무것도 나가지 않는다.
        // 순서를 뒤집으면 방금 Live 가 된 방의 egress 를 끊게 된다 — 이 검증이 그 회귀를 막는다.
        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(never()).save(org.mockito.ArgumentMatchers.any(LiveRoom.class));
    }
}
