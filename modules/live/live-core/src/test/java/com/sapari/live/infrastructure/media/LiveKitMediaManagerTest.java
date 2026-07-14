package com.sapari.live.infrastructure.media;

import io.livekit.server.AudioMixing;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.IngressServiceClient;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitEgress.EgressStatus;
import livekit.LivekitEgress.EncodingOptions;
import livekit.LivekitEgress.EncodingOptionsPreset;
import livekit.LivekitEgress.SegmentedFileOutput;
import livekit.LivekitIngress.IngressInfo;
import livekit.LivekitIngress.IngressInput;
import retrofit2.Call;
import retrofit2.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.MasterPlaylistPublisher;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.infrastructure.config.LiveKitProperties;

@ExtendWith(MockitoExtension.class)
public class LiveKitMediaManagerTest {

    @Mock
    private RoomServiceClient roomServiceClient;
    @Mock
    private EgressServiceClient egressServiceClient;
    @Mock
    private IngressServiceClient ingressServiceClient;
    @Mock
    private ObjectProvider<MasterPlaylistPublisher> masterPlaylistPublisher;

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
        liveKitMediaManager = new LiveKitMediaManager(
                roomServiceClient, liveKitProperties, egressServiceClient, ingressServiceClient, masterPlaylistPublisher);
        egressId = "egress-" + UUID.randomUUID();
        roomId = UUID.randomUUID();
    }

    @RepeatedTest(value = 10)
    @DisplayName("HLS Egress 시작: 업로더 미배선이면 기본 화질(720p) 1개만 인코딩하고 720p를 서빙한다")
    void startHlsEgress_singleRendition_whenNoPublisher() throws IOException {
        UUID roomId = UUID.randomUUID();

        EgressInfo mockEgressInfo = fixtureMonkey.giveMeOne(EgressInfo.class);

        Call<EgressInfo> mockCall = mock(Call.class);
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
        )).willReturn(mockCall);
        given(mockCall.execute()).willReturn(Response.success(mockEgressInfo));
        given(masterPlaylistPublisher.getIfAvailable()).willReturn(null); // 업로더 미배선 → ABR 비활성

        // when
        HlsEgressResult result = liveKitMediaManager.startHlsEgress(roomId);

        // then: 비기본 화질(1080p·360p)은 스킵 → 720p 1개만 시작(낭비 방지 가드)
        then(egressServiceClient).should(times(1)).startRoomCompositeEgress(
                anyString(), any(SegmentedFileOutput.class), anyString(),
                nullable(EncodingOptionsPreset.class), nullable(EncodingOptions.class),
                anyBoolean(), anyBoolean(), anyString(), any(AudioMixing.class));
        assertThat(result.egressId()).isEqualTo(mockEgressInfo.getEgressId());
        assertThat(result.hlsUrl()).contains(liveKitProperties.hls().cdnBaseUrl());
        assertThat(result.hlsUrl()).contains("720p");
        assertThat(result.hlsUrl()).doesNotContain("master.m3u8");
    }

    @Test
    @DisplayName("HLS Egress 시작(ABR): 화질별 경로를 filename_prefix·playlist_name 모두에 담는다(안전 형태)")
    void startHlsEgress_buildsPerRenditionPaths_whenAbr() throws IOException {
        UUID roomId = UUID.randomUUID();
        EgressInfo info = EgressInfo.newBuilder().setEgressId("eg").build();
        Call<EgressInfo> call = mock(Call.class);
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
        )).willReturn(call);
        given(call.execute()).willReturn(Response.success(info));
        given(masterPlaylistPublisher.getIfAvailable())
                .willReturn(mock(MasterPlaylistPublisher.class)); // ABR ON → 3화질

        liveKitMediaManager.startHlsEgress(roomId);

        ArgumentCaptor<SegmentedFileOutput> captor = ArgumentCaptor.forClass(SegmentedFileOutput.class);
        then(egressServiceClient).should(times(3)).startRoomCompositeEgress(
                anyString(),
                captor.capture(),
                anyString(),
                nullable(EncodingOptionsPreset.class),
                nullable(EncodingOptions.class),
                anyBoolean(),
                anyBoolean(),
                anyString(),
                any(AudioMixing.class));

        String base = liveKitProperties.s3().keyPrefix() + roomId + "/"; // "live/{roomId}/"
        List<SegmentedFileOutput> outputs = captor.getAllValues();

        // playlist_name·live_playlist_name = 화질별 디렉터리 + index.m3u8
        assertThat(outputs).extracting(SegmentedFileOutput::getPlaylistName)
                .containsExactlyInAnyOrder(
                        base + "1080p/index.m3u8",
                        base + "720p/index.m3u8",
                        base + "360p/index.m3u8");
        assertThat(outputs).extracting(SegmentedFileOutput::getLivePlaylistName)
                .containsExactlyInAnyOrder(
                        base + "1080p/index.m3u8",
                        base + "720p/index.m3u8",
                        base + "360p/index.m3u8");
        // 안전 형태: filename_prefix도 화질 경로 포함(LiveKit StorageDir 해석과 무관하게 같은 경로 보장)
        assertThat(outputs).extracting(SegmentedFileOutput::getFilenamePrefix)
                .containsExactlyInAnyOrder(
                        base + "1080p/segment_",
                        base + "720p/segment_",
                        base + "360p/segment_");
    }

    @Test
    @DisplayName("HLS Egress 시작: master 업로더가 있으면 master.m3u8을 게시하고 master URL을 반환한다")
    void startHlsEgress_publishesMaster_whenPublisherAvailable() throws IOException {
        UUID roomId = UUID.randomUUID();
        EgressInfo info = EgressInfo.newBuilder().setEgressId("eg").build();
        Call<EgressInfo> call = mock(Call.class);
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
        )).willReturn(call);
        given(call.execute()).willReturn(Response.success(info));

        MasterPlaylistPublisher publisher = mock(MasterPlaylistPublisher.class);
        given(masterPlaylistPublisher.getIfAvailable()).willReturn(publisher);

        HlsEgressResult result = liveKitMediaManager.startHlsEgress(roomId);

        String base = liveKitProperties.s3().keyPrefix() + roomId + "/"; // "live/{roomId}/"
        // master.m3u8(3화질 목차)을 같은 경로에 게시
        then(publisher).should().publish(eq(base + "master.m3u8"), contains("#EXT-X-STREAM-INF"));
        // 서빙 URL이 master로 전환됨
        assertThat(result.hlsUrl())
                .isEqualTo(liveKitProperties.hls().cdnBaseUrl() + "/" + base + "master.m3u8");
    }

    @Test
    @DisplayName("HLS Egress 시작: master 업로드 실패 시 예외 없이 720p 강등 + 비기본 화질 egress 중단(잔여 비용 차단)")
    void startHlsEgress_fallsBackTo720p_andStopsExtraEgress_whenMasterUploadFails() throws IOException {
        UUID roomId = UUID.randomUUID();

        // 화질별 distinct egressId (시작 순서: 1080p → 720p(기본) → 360p)
        Call<EgressInfo> call = mock(Call.class);
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
        )).willReturn(call);
        given(call.execute())
                .willReturn(Response.success(EgressInfo.newBuilder().setEgressId("eg-1080").build()))
                .willReturn(Response.success(EgressInfo.newBuilder().setEgressId("eg-720").build()))
                .willReturn(Response.success(EgressInfo.newBuilder().setEgressId("eg-360").build()));

        MasterPlaylistPublisher publisher = mock(MasterPlaylistPublisher.class);
        given(masterPlaylistPublisher.getIfAvailable()).willReturn(publisher);
        willThrow(new RuntimeException("object storage 다운"))
                .given(publisher).publish(anyString(), anyString());

        // 비기본 화질(1080p, 360p) 중단 stub
        Call stop1080 = mock(Call.class);
        given(egressServiceClient.stopEgress("eg-1080")).willReturn(stop1080);
        Call stop360 = mock(Call.class);
        given(egressServiceClient.stopEgress("eg-360")).willReturn(stop360);

        // when: 업로드 실패해도 예외가 밖으로 던져지지 않음
        HlsEgressResult result = liveKitMediaManager.startHlsEgress(roomId);

        // then: master 대신 기본 화질(720p) variant로 강등
        assertThat(result.hlsUrl()).contains("720p");
        assertThat(result.hlsUrl()).doesNotContain("master.m3u8");
        // 참조되지 않을 비기본 화질 egress는 중단, 기본(720p)은 유지
        then(stop1080).should().execute();
        then(stop360).should().execute();
        then(egressServiceClient).should(never()).stopEgress("eg-720");
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
        given(masterPlaylistPublisher.getIfAvailable())
                .willReturn(mock(MasterPlaylistPublisher.class)); // ABR ON → 다중 egress 시작(부분 실패 재현)

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
        given(masterPlaylistPublisher.getIfAvailable())
                .willReturn(mock(MasterPlaylistPublisher.class)); // ABR ON → 다중 egress 시작(부분 실패 재현)

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

    @Test
    @DisplayName("RTMP Ingress 발급 성공: RTMP_INPUT 으로 방(roomId)·판매자(sellerId) 배정, ingressId·url·streamKey 반환")
    void createIngress_Success() throws IOException {
        UUID sellerId = UUID.randomUUID();
        IngressInfo info = IngressInfo.newBuilder()
                .setIngressId("ingress-1")
                .setUrl("rtmp://livekit.example/live")
                .setStreamKey("super-secret-key")
                .build();
        Call<IngressInfo> call = mock(Call.class);
        given(ingressServiceClient.createIngress(
                anyString(), anyString(), anyString(), anyString(), any(IngressInput.class)))
                .willReturn(call);
        given(call.execute()).willReturn(Response.success(info));

        IngressResult result = liveKitMediaManager.createIngress(roomId, sellerId);

        // 결과 매핑
        assertThat(result.ingressId()).isEqualTo("ingress-1");
        assertThat(result.rtmpUrl()).isEqualTo("rtmp://livekit.example/live");
        assertThat(result.streamKey()).isEqualTo("super-secret-key");
        // 호출 계약: name=라벨("rtmp-"+roomId), roomName=roomId, identity·name=sellerId, RTMP 입력
        then(ingressServiceClient).should().createIngress(
                eq("rtmp-" + roomId), eq(roomId.toString()),
                eq(sellerId.toString()), eq(sellerId.toString()),
                eq(IngressInput.RTMP_INPUT));
    }

    @Test
    @DisplayName("RTMP Ingress 발급: 응답이 실패(비 2xx)면 LiveMediaException")
    void createIngress_httpFailure() throws IOException {
        Call<IngressInfo> call = mock(Call.class);
        Response failed = mock(Response.class);
        given(failed.isSuccessful()).willReturn(false);
        given(ingressServiceClient.createIngress(
                anyString(), anyString(), anyString(), anyString(), any(IngressInput.class)))
                .willReturn(call);
        given(call.execute()).willReturn(failed);

        assertThrows(LiveMediaException.class,
                () -> liveKitMediaManager.createIngress(roomId, UUID.randomUUID()));
    }

    @Test
    @DisplayName("RTMP Ingress 발급: 통신 오류(IOException)를 LiveMediaException 으로 번역")
    void createIngress_ioError() throws IOException {
        Call<IngressInfo> call = mock(Call.class);
        given(ingressServiceClient.createIngress(
                anyString(), anyString(), anyString(), anyString(), any(IngressInput.class)))
                .willReturn(call);
        given(call.execute()).willThrow(new IOException("네트워크 실패"));

        assertThrows(LiveMediaException.class,
                () -> liveKitMediaManager.createIngress(roomId, UUID.randomUUID()));
    }
}
