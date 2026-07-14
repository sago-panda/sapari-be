package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveEventPublisher;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
public class EndLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private LiveEventPublisher liveEventPublisher;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private EndLiveService endLiveService;

    private FixtureMonkey fixtureMonkey;
    private UUID roomId;
    private UUID sellerId;

    @BeforeEach
    public void setup(){
        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();

        roomId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 종료 성공: 정상적인 커맨드가 주어지면 미디어 서버를 종료하고 상태를 저장한다")
    void endLive_Success() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);

        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");

        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", liveStatus)
                .set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc())
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());

        // when
        endLiveService.end(command);

        // then
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId, mockRoom.streamInfo().egressId());
        then(liveMediaManager).should(times(1)).closeRoom(mockRoom.streamInfo().sfuRoomId());

        ArgumentCaptor<LiveRoom> roomCaptor = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should(times(1)).save(roomCaptor.capture());

        // 3. 가로챈 객체의 상태 검증
        LiveRoom savedRoom = roomCaptor.getValue();

        assertThat(savedRoom.id()).isEqualTo(command.roomId());
        assertThat(savedRoom.status()).isInstanceOf(LiveStatus.Ended.class);
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 종료 실패: 방을 찾을 수 없거나 권한이 없으면 LiveNotFoundException 발생")
    void endLive_Fail_NotFound() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);

        given(liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId()))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> endLiveService.end(command))
                .isInstanceOf(LiveNotFoundException.class)
                .hasMessageContaining(command.roomId().toString());

        // 미디어 매니저나 save 로직이 절대 실행되지 않았음을 검증
        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(times(0)).save(any());
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 종료 실패: 방송 상태가 LIVE가 아니면 InvalidLiveStateException 발생")
    void endLive_Fail_NotLiveStatus() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        LiveRoom notLiveRoom = LiveRoom.builder()
                .id(command.roomId())
                .sellerId(command.sellerId())
                .status(new LiveStatus.Scheduled(Instant.now()))
                .title("테스트 방송")
                .build();

        given(liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId()))
                .willReturn(Optional.of(notLiveRoom));

        // when & then
        assertThatThrownBy(() -> endLiveService.end(command))
                .isInstanceOf(InvalidLiveStateException.class)
                .hasMessageContaining(command.roomId().toString());

        // 검증
        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(times(0)).save(any());
    }

    @Test
    @DisplayName("종료 커밋 후 RoomEnded를 발행한다 (afterCommit)")
    void publishesRoomEnded_afterCommit() {
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        Instant endedAt = Instant.parse("2026-07-03T00:00:00Z");
        LiveStatus.Live liveStatus = new LiveStatus.Live(endedAt, "sfu", "eg", "http://hls/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu", "eg", "http://hls/index.m3u8");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc()).sample();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(endedAt);

        TransactionSynchronizationManager.initSynchronization();
        try {
            endLiveService.end(command);

            // 커밋 전에는 미발행
            then(liveEventPublisher).shouldHaveNoInteractions();

            // afterCommit 수동 트리거 → 발행
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            then(liveEventPublisher).should(times(1)).publishRoomEnded(roomId, endedAt);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("종료가 롤백되면(afterCommit 미호출) RoomEnded를 발행하지 않는다")
    void doesNotPublish_whenRolledBack() {
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        Instant endedAt = Instant.parse("2026-07-03T00:00:00Z");
        LiveStatus.Live liveStatus = new LiveStatus.Live(endedAt, "sfu", "eg", "http://hls/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu", "eg", "http://hls/index.m3u8");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc()).sample();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(endedAt);

        TransactionSynchronizationManager.initSynchronization();
        try {
            endLiveService.end(command);
            // 롤백 시나리오: afterCommit을 호출하지 않는다 → 발행 없음
            then(liveEventPublisher).shouldHaveNoInteractions();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
