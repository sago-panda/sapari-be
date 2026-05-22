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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
public class EndLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

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

        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", fixtureMonkey.giveMeOne(LiveStatus.Live.class))
                .setNotNull("streamInfo")
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.of(mockRoom));

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
        LiveStatus nonLiveStatus = fixtureMonkey.giveMeBuilder(LiveStatus.class)
                .setPostCondition(status -> !(status instanceof LiveStatus.Live || status instanceof LiveStatus.Suspended))
                .sample();

        // 생성된 무작위 nonLiveStatus를 LiveRoom에 주입
        LiveRoom notLiveRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", command.roomId())
                .set("sellerId", command.sellerId())
                .set("status", nonLiveStatus)
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId()))
                .willReturn(Optional.of(notLiveRoom));

        // when & then
        assertThatThrownBy(() -> endLiveService.end(command))
                .isInstanceOf(InvalidLiveStateException.class)
                .hasMessageContaining("방송 중인 방만 종료 가능합니다");

        // 검증
        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(times(0)).save(any());
    }
}
