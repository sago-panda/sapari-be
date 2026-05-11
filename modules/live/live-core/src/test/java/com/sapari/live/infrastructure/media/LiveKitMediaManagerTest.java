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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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

    @BeforeEach
    void setup(){
        LiveKitProperties.S3 s3 = fixtureMonkey.giveMeOne(LiveKitProperties.S3.class);
        LiveKitProperties.Hls hls = fixtureMonkey.giveMeOne(LiveKitProperties.Hls.class);
        liveKitProperties = fixtureMonkey.giveMeBuilder(LiveKitProperties.class)
                .set("s3", s3)
                .set("hls", hls)
                .sample();
        liveKitMediaManager = new LiveKitMediaManager(roomServiceClient, liveKitProperties, egressServiceClient);
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
}
