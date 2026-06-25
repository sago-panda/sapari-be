package com.sapari.live.infrastructure.media;

import io.livekit.server.AudioMixing;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitEgress.EgressStatus;
import livekit.LivekitEgress.EncodingOptions;
import livekit.LivekitEgress.EncodingOptionsPreset;
import livekit.LivekitEgress.SegmentedFileOutput;
import retrofit2.Call;
import retrofit2.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.domain.exception.LiveMediaException;
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
        LiveKitProperties.S3 s3 = fixtureMonkey.giveMeBuilder(LiveKitProperties.S3.class)
                .set("bucket", "test-bucket")
                .set("accessKey", "test-access-key")
                .set("secretKey", "test-secret-key")
                .set("region", "ap-northeast-2")
                .set("keyPrefix", "live/")
                .sample();
        LiveKitProperties.Hls hls = fixtureMonkey.giveMeBuilder(LiveKitProperties.Hls.class)
                .set("cdnBaseUrl", "https://cdn.example.com")
                .set("segmentDuration", 2)
                .sample();
        liveKitProperties = fixtureMonkey.giveMeBuilder(LiveKitProperties.class)
                .set("s3", s3)
                .set("hls", hls)
                .sample();
        liveKitMediaManager = new LiveKitMediaManager(roomServiceClient, liveKitProperties, egressServiceClient);
        egressId = "egress-" + UUID.randomUUID();
        roomId = UUID.randomUUID();
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 시작: 1080p/720p/360p 세 화질을 각각 egress로 시작한다")
    void startHlsEgressTest() throws IOException {
        UUID roomId = UUID.randomUUID();

        EgressInfo mockEgressInfo = fixtureMonkey.giveMeOne(EgressInfo.class);


        Call<EgressInfo> mockCall = mock(Call.class);
        given(egressServiceClient.startRoomCompositeEgress(
                anyString(),
                any(SegmentedFileOutput.class),
                anyString(),
                nullable(EncodingOptionsPreset.class), // 360p는 preset=null + custom EncodingOptions로 호출
                nullable(EncodingOptions.class),
                anyBoolean(),
                anyBoolean(),
                anyString(),
                any(AudioMixing.class)
        )).willReturn(mockCall);
        given(mockCall.execute()).willReturn(Response.success(mockEgressInfo));

        // when
        HlsEgressResult result = liveKitMediaManager.startHlsEgress(roomId);

        // then
        // 세 화질을 각각 egress로 시작
        then(egressServiceClient).should(times(3)).startRoomCompositeEgress(
                anyString(), any(SegmentedFileOutput.class), anyString(),
                nullable(EncodingOptionsPreset.class), nullable(EncodingOptions.class),
                anyBoolean(), anyBoolean(), anyString(), any(AudioMixing.class));
        // 대표(720p) egressId를 반환
        assertThat(result.egressId()).isEqualTo(mockEgressInfo.getEgressId());
        // URL 조합이 도메인 규칙을 따르는지 검증
        assertThat(result.hlsUrl()).contains(liveKitProperties.hls().cdnBaseUrl());
        assertThat(result.hlsUrl()).contains(liveKitProperties.s3().keyPrefix());
        assertThat(result.hlsUrl()).contains("720p");
    }

    @Test
    @DisplayName("HLS Egress 시작 중 일부 화질 실패: 시작에 성공한 egress를 직접 보상 중단하고 예외를 재던진다")
    void startHlsEgress_partialFailure_compensatesStartedEgress() throws IOException {
        UUID roomId = UUID.randomUUID();

        // 1번째 화질(1080p)은 성공, 2번째에서 통신 실패
        EgressInfo first = EgressInfo.newBuilder().setEgressId("egress-1080").build();
        Call<EgressInfo> startCall = mock(Call.class);
        given(egressServiceClient.startRoomCompositeEgress(
                anyString(),
                any(SegmentedFileOutput.class),
                anyString(),
                nullable(EncodingOptionsPreset.class),
                nullable(EncodingOptions.class),
                anyBoolean(),
                anyBoolean(),
                anyString(),
                any(AudioMixing.class)
        )).willReturn(startCall);
        given(startCall.execute())
                .willReturn(Response.success(first))
                .willThrow(new IOException("네트워크 실패"));

        // 보상: 시작분(egress-1080) 직접 중단 + listEgress 백스톱(빈 목록)
        Call stopCall = mock(Call.class);
        given(egressServiceClient.stopEgress("egress-1080")).willReturn(stopCall);
        Call listCall = mock(Call.class);
        given(egressServiceClient.listEgress(roomId.toString())).willReturn(listCall);
        given(listCall.execute()).willReturn(Response.success(List.<EgressInfo>of()));

        // when & then
        assertThrows(LiveMediaException.class, () -> liveKitMediaManager.startHlsEgress(roomId));

        // 추적된 시작분 egress가 직접 중단됨 (listEgress 전파 지연에 의존하지 않음)
        then(stopCall).should(times(1)).execute();
    }

    @Test
    @DisplayName("HLS Egress 시작 부분 실패: 추적 못 한 egress는 listEgress 백스톱으로 중단한다")
    void startHlsEgress_partialFailure_backstopStopsUntrackedEgress() throws IOException {
        UUID roomId = UUID.randomUUID();

        // 1번째 화질은 성공(추적됨), 2번째에서 통신 실패
        EgressInfo tracked = EgressInfo.newBuilder().setEgressId("egress-1080").build();
        Call<EgressInfo> startCall = mock(Call.class);
        given(egressServiceClient.startRoomCompositeEgress(
                anyString(),
                any(SegmentedFileOutput.class),
                anyString(),
                nullable(EncodingOptionsPreset.class),
                nullable(EncodingOptions.class),
                anyBoolean(),
                anyBoolean(),
                anyString(),
                any(AudioMixing.class)
        )).willReturn(startCall);
        given(startCall.execute())
                .willReturn(Response.success(tracked))
                .willThrow(new IOException("네트워크 실패"));

        // 직접 추적분 중단
        Call directStop = mock(Call.class);
        given(egressServiceClient.stopEgress("egress-1080")).willReturn(directStop);

        // 백스톱: listEgress가 추적 못 한 ACTIVE egress를 반환 → 이것도 중단돼야 함
        EgressInfo untracked = EgressInfo.newBuilder()
                .setEgressId("egress-orphan")
                .setStatus(EgressStatus.EGRESS_ACTIVE)
                .build();
        Call listCall = mock(Call.class);
        given(egressServiceClient.listEgress(roomId.toString())).willReturn(listCall);
        given(listCall.execute()).willReturn(Response.success(List.of(untracked)));
        Call orphanStop = mock(Call.class);
        given(egressServiceClient.stopEgress("egress-orphan")).willReturn(orphanStop);

        // when & then
        assertThrows(LiveMediaException.class, () -> liveKitMediaManager.startHlsEgress(roomId));

        // 백스톱이 추적 못 한 고아 egress를 중단
        then(orphanStop).should(times(1)).execute();
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 중단 성공: room의 active egress를 listEgress로 조회해 일괄 중단한다")
    void stopHlsEgress_Success() throws IOException {
        // given: room에 active egress 1건
        EgressInfo activeEgress = EgressInfo.newBuilder()
                .setEgressId(egressId)
                .setStatus(EgressStatus.EGRESS_ACTIVE)
                .build();
        Call listCall = mock(Call.class);
        given(egressServiceClient.listEgress(roomId.toString())).willReturn(listCall);
        given(listCall.execute()).willReturn(Response.success(List.of(activeEgress)));

        Call stopCall = mock(Call.class);
        given(egressServiceClient.stopEgress(egressId)).willReturn(stopCall);

        // when
        liveKitMediaManager.stopHlsEgress(roomId, egressId);

        // then
        then(stopCall).should(times(1)).execute();
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 중단 예외 처리: 외부 통신 에러가 발생해도 예외를 삼키고 정상 종료된다")
    void stopHlsEgress_ExceptionHandled() throws IOException {
        // given: listEgress 통신 단계에서 예외
        Call listCall = mock(Call.class);
        given(egressServiceClient.listEgress(roomId.toString())).willReturn(listCall);
        given(listCall.execute()).willThrow(new RuntimeException("네트워크 타임아웃"));

        // when & then: 예외를 삼키고 정상 종료
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
