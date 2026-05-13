package com.sapari.live.infrastructure.media;

import io.livekit.server.AudioMixing;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitEgress.EncodingOptions;
import livekit.LivekitEgress.EncodingOptionsPreset;
import livekit.LivekitEgress.SegmentedFileOutput;
import retrofit2.Call;
import retrofit2.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.infrastructure.config.LiveKitProperties;

@ExtendWith(MockitoExtension.class)
public class LiveKitMediaManagerTest {

    @Mock
    private RoomServiceClient roomServiceClient;
    @Mock
    private EgressServiceClient egressServiceClient;

    private static final FixtureMonkey fixtureMonkey = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .build();

    private LiveKitProperties liveKitProperties;
    private LiveKitMediaManager liveKitMediaManager;
    private UUID roomId;
    private String egressId;

    @BeforeEach
    void setup(){
        LiveKitProperties.S3 s3 = fixtureMonkey.giveMeOne(LiveKitProperties.S3.class);
        LiveKitProperties.Hls hls = fixtureMonkey.giveMeOne(LiveKitProperties.Hls.class);
        liveKitProperties = fixtureMonkey.giveMeBuilder(LiveKitProperties.class)
                .set("s3", s3)
                .set("hls", hls)
                .sample();
        liveKitMediaManager = new LiveKitMediaManager(roomServiceClient, liveKitProperties, egressServiceClient);
        egressId = fixtureMonkey.giveMeOne(String.class);
        roomId = UUID.randomUUID();
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 시작: 랜덤 생성 데이터로도 정상 작동")
    void startHlsEgressTest() throws IOException {
        UUID roomId = UUID.randomUUID();

        EgressInfo mockEgressInfo = fixtureMonkey.giveMeOne(EgressInfo.class);


        Call<EgressInfo> mockCall = mock(Call.class);
        given(egressServiceClient.startRoomCompositeEgress(
                anyString(),
                any(SegmentedFileOutput.class),  // ✨ 핵심 수정: EncodedFileOutput -> SegmentedFileOutput
                anyString(),
                any(EncodingOptionsPreset.class),
                nullable(EncodingOptions.class), // null이 들어올 수 있는 객체는 nullable 권장
                anyBoolean(),
                anyBoolean(),
                anyString(),
                any(AudioMixing.class)           // 9번째 인자 타입 명시
        )).willReturn(mockCall);
        given(mockCall.execute()).willReturn(Response.success(mockEgressInfo));

        // when
        HlsEgressResult result = liveKitMediaManager.startHlsEgress(roomId);

        // then
        assertThat(result.egressId()).isEqualTo(mockEgressInfo.getEgressId());
        // URL 조합이 도메인 규칙을 따르는지 검증
        assertThat(result.hlsUrl()).contains(liveKitProperties.hls().cdnBaseUrl());
        assertThat(result.hlsUrl()).contains(liveKitProperties.s3().keyPrefix());
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 중단 성공: EgressClient의 stopEgress가 정상 호출된다")
    void stopHlsEgress_Success() throws IOException {
        // given
        Call mockCall = mock(Call.class);
        given(egressServiceClient.stopEgress(egressId)).willReturn(mockCall);

        // when
        liveKitMediaManager.stopHlsEgress(roomId, egressId);

        // then
        then(mockCall).should(times(1)).execute();
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 중단 예외 처리: 외부 통신 에러가 발생해도 예외를 삼키고 정상 종료된다")
    void stopHlsEgress_ExceptionHandled() throws IOException {
        // given
        UUID roomId = UUID.randomUUID();
        String egressId = "egress-123";
        Call mockCall = mock(Call.class);

        given(egressServiceClient.stopEgress(egressId)).willReturn(mockCall);
        // execute() 호출 시 예외가 발생하도록 조작
        given(mockCall.execute()).willThrow(new RuntimeException("네트워크 타임아웃"));

        // when & then
        // assertDoesNotThrow를 통해 내부에서 에러가 터져도 밖으로 던져지지 않음을 검증
        assertDoesNotThrow(() -> liveKitMediaManager.stopHlsEgress(roomId, egressId));
    }

    @RepeatedTest(value = 10)
    @DisplayName("SFU Room 삭제 성공: RoomClient의 deleteRoom이 정상 호출된다")
    void closeRoom_Success() throws IOException {
        // given
        String sfuRoomId = "sfu-room-456";
        Call mockCall = mock(Call.class);

        given(roomServiceClient.deleteRoom(sfuRoomId)).willReturn(mockCall);

        // when
        liveKitMediaManager.closeRoom(sfuRoomId);

        // then
        then(mockCall).should(times(1)).execute();
    }

    @RepeatedTest(value = 10)
    @DisplayName("SFU Room 삭제 예외 처리: 방이 이미 삭제되어 에러가 발생해도 정상 종료된다")
    void closeRoom_ExceptionHandled() throws IOException {
        // given
        String sfuRoomId = "sfu-room-456";
        Call mockCall = mock(Call.class);

        given(roomServiceClient.deleteRoom(sfuRoomId)).willReturn(mockCall);
        given(mockCall.execute()).willThrow(new RuntimeException("Room not found"));

        // when & then
        assertDoesNotThrow(() -> liveKitMediaManager.closeRoom(sfuRoomId));
    }
}
