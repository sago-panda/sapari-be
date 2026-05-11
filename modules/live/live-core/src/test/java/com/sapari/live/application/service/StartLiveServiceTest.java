package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStatus.Live;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
public class StartLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @InjectMocks
    private StartLiveService startLiveService;

    private FixtureMonkey fixtureMonkey;

    private StartLiveCommand command;
    private UUID roomId;
    private UUID sellerId;

//    @Override
//    @Transactional
//    public StartLiveResult execute(StartLiveCommand command){
//        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
//                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));
//
//        // Live로 넘어갈 수 있는 상태인지 확인
//        if(!room.status().canTransitionTo(new LiveStatus.Live(null, null, null, null))){
//            throw new InvalidLiveStateException(room.id().toString());
//        }
//
//        // 판매자가 방송을 송출하기 위한 SFU 토큰 발급
//        String sfuToken = liveMediaManager.issueSellerToken(command.roomId(), command.sellerId());
//        log.warn("판매자: {}, sfuToken 발급: {}", command.sellerId().toString(), sfuToken);
//
//        // HLS Egress 시작
//        // 방송자가 publish하면 자동으로 S3에 세그먼트 기록
//        // egressId는 나중에 중단할 때 필요
//        HlsEgressResult egressResult = liveMediaManager.startHlsEgress(command.roomId());
//
//        // liveRoomEntity status -> Live 변환
//        StreamInfo streamInfo = StreamInfo.of(room.streamInfo().sfuRoomId(), egressResult.egressId(), egressResult.hlsUrl());
//        LiveRoom updatedRoom = room.startLive(streamInfo);
//
//        //TODO: 도메인 이벤트 발행하여 연결된 시청자에게 방송 시작 이벤트 전송
//
//        //저장
//        liveRoomRepository.save(updatedRoom);
//
//        return new StartLiveResult(
//                command.roomId().toString(),
//                sfuToken,
//                streamInfo.hlsUrl(),
//                ""
//        );
//    }

    @BeforeEach
    void setup(){
        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();

        roomId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        command = new StartLiveCommand(roomId, sellerId);

    }

    //TODO: 방송 시작 성공 후 알림 발송 추가
    @RepeatedTest(value = 10)
    @DisplayName("방송 시작 성공: 유효한 요청 시 토큰과 HLS URL을 반환하고 방 상태를 업데이트한다.")
    void execute_success(){
        //given
        String expectedSfuToken = "sfu-token-123";
        HlsEgressResult egressResult = new HlsEgressResult("egress-123", "http://hls.url/index.m3u8");
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", "egress-123", "http://hls.url/index.m3u8");
        LiveRoom room = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(LocalDateTime.now()))
                .set("streamInfo", streamInfo)
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(room));
        given(liveMediaManager.issueSellerToken(roomId, sellerId)).willReturn(expectedSfuToken);
        given(liveMediaManager.startHlsEgress(roomId)).willReturn(egressResult);

        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(invocation -> invocation.getArgument(0));

        //when
        var result = startLiveService.execute(command);

        //then
        assertThat(result.roomId()).isEqualTo(roomId.toString());
        assertThat(result.sfuToken()).isEqualTo(expectedSfuToken);
        assertThat(result.hlsUrl()).isEqualTo(egressResult.hlsUrl());

        // 검증: Repository와 MediaManager가 올바르게 호출되었는지 확인
        verify(liveMediaManager).issueSellerToken(roomId, sellerId);
        verify(liveMediaManager).startHlsEgress(roomId);
        verify(liveRoomRepository).save(any(LiveRoom.class));
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 시작 실패: 해당하는 방을 찾을 수 없어 에러 발생")
    void execute_fail_room_not_found(){
        // given
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> startLiveService.execute(command))
                .isInstanceOf(LiveNotFoundException.class)
                .hasMessageContaining(roomId.toString());

    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 시작 실패: 이미 방송 중이거나 종료된 방")
    void execute_fail_when_invalid_status(){
        // given
        // 1. 방송을 시작할 수 없는 상태(ex: 이미 종료된 상태)로 세팅
        StreamInfo streamInfo = new StreamInfo("sfu-roomId-001", "egress-123", "http://hls.url/index.m3u8");

        LiveRoom invalidRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("streamInfo", streamInfo)
                .set("status", new Live(LocalDateTime.now(), streamInfo.sfuRoomId(), streamInfo.egressId(), streamInfo.hlsUrl()))
                .sample();

        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId)).willReturn(Optional.of(invalidRoom));

        // when & then
        assertThatThrownBy(() -> startLiveService.execute(command))
                .isInstanceOf(InvalidLiveStateException.class)
                .hasMessageContaining(roomId.toString());
    }


}
