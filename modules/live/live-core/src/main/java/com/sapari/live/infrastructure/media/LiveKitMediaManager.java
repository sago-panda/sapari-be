package com.sapari.live.infrastructure.media;

import io.livekit.server.AccessToken;
import io.livekit.server.AudioMixing;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitEgress.EgressInfo;
import livekit.LivekitEgress.EncodingOptionsPreset;
import livekit.LivekitEgress.S3Upload;
import livekit.LivekitEgress.SegmentedFileOutput;
import livekit.LivekitEgress.SegmentedFileProtocol;
import livekit.LivekitModels.Room;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.SfuRoomResult;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.infrastructure.config.LiveKitProperties;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitMediaManager implements LiveMediaManager {

    private final RoomServiceClient roomServiceClient;
    private final LiveKitProperties liveKitProperties;
    private static final int EMPTY_TIMEOUT = 300;
    private static final int MAX_PARTICIPANTS = 1000;
    private final EgressServiceClient egressServiceClient;

    @Override
    public SfuRoomResult createRoom(UUID roomId){
        try{
            Room room = roomServiceClient.createRoom(
                    roomId.toString(),
                    EMPTY_TIMEOUT,
                    MAX_PARTICIPANTS
            ).execute().body();

            log.info("LiveKit 룸 생성 완료: roomId={}", roomId);
            return new SfuRoomResult(room.getName(), room.getMaxParticipants(), true);
        } catch (IOException e){
            log.error("LiveKit 룸 생성 실패: roomId={}", roomId, e);
            throw new LiveMediaException("SFU 룸 생성 실패: " + roomId, e);
        }
    }

    /**
     * 방송자 토큰 생성 과정
     * accessToken의 기본 ttl이 6시간이므로 생략
     */
    @Override
    public String issueSellerToken(UUID roomId, UUID sellerId){
        AccessToken accessToken = new AccessToken(liveKitProperties.apiKey(), liveKitProperties.apiSecret());
        accessToken.setName(sellerId.toString());
        accessToken.setIdentity(sellerId.toString());

        accessToken.addGrants(
                new RoomJoin(true),
                new RoomName(roomId.toString()),
                new CanPublish(true),
                new CanSubscribe(true),
                new CanPublishData(true)
        );

        return accessToken.toJwt();
    }

    /**
     * HLS Egress 시작
     * 방송 -> S3 -> CDN
     */
    @Override
    public HlsEgressResult startHlsEgress(UUID roomId){
        LiveKitProperties.S3 s3 = liveKitProperties.s3();
        LiveKitProperties.Hls hls = liveKitProperties.hls();

        //S3 설정
        S3Upload s3Upload = S3Upload.newBuilder()
                .setBucket(s3.bucket())
                .setAccessKey(s3.accessKey())
                .setSecret(s3.secretKey())
                .setRegion(s3.region())
                .build();

        //hls 의 segment 설정
        SegmentedFileOutput hlsOutput = SegmentedFileOutput.newBuilder()
                .setProtocol(SegmentedFileProtocol.HLS_PROTOCOL)
                .setFilenamePrefix("segment_")
                .setPlaylistName("index.m3u8")
                .setSegmentDuration(hls.segmentDuration())
                .setLivePlaylistName("index.m3u8")
                .setS3(s3Upload)
                .build();

        try{
            // RoomCompositeEgress 시작
            // AudioMixing이 필수인 경우에는 인자 추가 (하단 주석 표시)
            Call<EgressInfo> call = egressServiceClient.startRoomCompositeEgress(
                    roomId.toString(),
                    hlsOutput,
                    "speaker-dark",
                    EncodingOptionsPreset.H264_720P_30,
                    null,
                    false,
                    false,
                    "" //custom base url
                    , AudioMixing.DEFAULT_MIXING
            );

            // API 실행 및 응답 대기
            Response<EgressInfo> response = call.execute();

            //응답 상태 검증, 호출 실패 시 에러
            if(!response.isSuccessful() || response.body() == null){
                log.error("LiveKit HLS Egress API 호출 실패: roomId={}, code={}, message={}",
                        roomId, response.code(), response.message());
                throw new LiveMediaException("HLS Egress 요청에 실패했습니다.");
            }

            EgressInfo egressInfo = response.body();

            String hlsUrl = hls.cdnBaseUrl() + "/" + s3.keyPrefix() + roomId + "/index.m3u8";
            log.info("HLS Egress 시작: roomId={}, egressId={}, hlsUrl={}",
                    roomId, egressInfo.getEgressId(), hlsUrl);

            return new HlsEgressResult(egressInfo.getEgressId(), hlsUrl);

        } catch (IOException e){
            log.error("LiveKit HLS Egress 통신 중 에러 발생: roomId={}", roomId, e);
            throw new LiveMediaException("HLS Egress 시작 중 통신 오류: " + roomId, e);
        } catch (Exception e) {
            log.error("LiveKit HLS Egress 처리 중 예상치 못한 에러 발생: roomId={}", roomId, e);
            throw new LiveMediaException("HLS Egress 알 수 없는 오류: " + roomId, e);
        }
    }

    /**
     * s3 기록 종료, 최종 플레이 리스트 생성
     */
    @Override
    public void stopHlsEgress(UUID roomId, String egressId){
        try{
            egressServiceClient.stopEgress(egressId).execute();
            log.info("HLS Egress 중단: egressId: {}, roomId: {}", egressId, roomId);
        }catch (Exception e){
            log.warn("HLS Egress 중단 실패 (이미 중단됐을 수 있음): egressId={}", egressId, e);
        }
    }

    /**
     * sfu room 삭제
     */
    @Override
    public void closeRoom(String sfuRoomId){
        try{
            roomServiceClient.deleteRoom(sfuRoomId).execute();
            log.info("LiveKit 룸 삭제: sfuRoomId={}", sfuRoomId);
        }catch (Exception e){
            log.warn("LiveKit 룸 삭제 실패 (이미 삭제됐을 수 있음): sfuRoomId={}", sfuRoomId, e);
        }
    }
}
