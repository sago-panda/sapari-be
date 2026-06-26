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
import livekit.LivekitEgress.EgressStatus;
import livekit.LivekitEgress.EncodingOptions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.MasterPlaylistPublisher;
import com.sapari.live.application.port.SfuRoomResult;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.infrastructure.config.LiveKitProperties;
import com.sapari.global.validator.UrlValidator;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitMediaManager implements LiveMediaManager {

    private final RoomServiceClient roomServiceClient;
    private final LiveKitProperties liveKitProperties;
    private static final int EMPTY_TIMEOUT = 300;
    private static final int MAX_PARTICIPANTS = 1000;
    private static final String LAYOUT = "speaker-dark";
    // master.m3u8 업로드 도입 전까지 서빙하는 대표 화질 (이 화질의 변형 m3u8을 직접 재생)
    private static final HlsRendition DEFAULT_RENDITION = HlsRendition.P720;
    private final EgressServiceClient egressServiceClient;
    // master 업로더(NCP 어댑터)는 링크 확정 후 빈으로 등록 — 없으면 720p 강등(ObjectProvider로 선택 주입)
    private final ObjectProvider<MasterPlaylistPublisher> masterPlaylistPublisher;

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
     * HLS Egress 시작 — 1080p/720p/360p 세 화질을 각각 독립 egress로 띄운다.
     * 방송 -> (화질별) S3 경로 -> CDN. 화질을 묶는 master.m3u8(ABR) 생성은 별도 작업.
     */
    @Override
    public HlsEgressResult startHlsEgress(UUID roomId){
        LiveKitProperties.S3 s3 = liveKitProperties.s3();
        LiveKitProperties.Hls hls = liveKitProperties.hls();

        //S3 설정 (세 화질이 공유)
        S3Upload s3Upload = S3Upload.newBuilder()
                .setBucket(s3.bucket())
                .setAccessKey(s3.accessKey())
                .setSecret(s3.secretKey())
                .setRegion(s3.region())
                .build();

        String basePath = s3.keyPrefix() + roomId + "/";

        // 업로더(NCP 어댑터)가 없으면 master.m3u8을 못 줘서 ABR 불가 → 기본 화질만 인코딩(1080p·360p 스킵)해
        // "3배 인코딩하고 720p만 서빙"하는 낭비를 막는다. 어댑터 빈이 등록되면 자동으로 전 화질 + master로 전환.
        MasterPlaylistPublisher publisher = masterPlaylistPublisher.getIfAvailable();
        boolean abrEnabled = publisher != null;

        // 시작에 성공한 egressId를 직접 추적 — 보상 시 listEgress 전파 지연(TOCTOU)에 기대지 않고 이들을 바로 중단한다.
        List<String> startedEgressIds = new ArrayList<>();

        try {
            // HlsRendition(단일 출처)을 돌며 화질별 egress를 시작. master.m3u8도 같은 enum을 쓰므로 화질 목록이 항상 일치.
            String defaultEgressId = null;
            for (HlsRendition rendition : HlsRendition.values()) {
                if (!abrEnabled && rendition != DEFAULT_RENDITION) {
                    continue; // ABR 불가 시 비기본 화질은 인코딩하지 않음(낭비 방지)
                }
                String egressId = startRenditionEgress(roomId, s3Upload, basePath + rendition.getPathSegment() + "/",
                        presetFor(rendition), customEncodingFor(rendition), hls.segmentDuration());
                startedEgressIds.add(egressId);
                if (rendition == DEFAULT_RENDITION) {
                    defaultEgressId = egressId;
                }
            }

            // 업로더가 있으면 master.m3u8 게시 후 master URL, 없으면(또는 업로드 실패) 기본 화질 variant 직접 서빙.
            String hlsUrl = publishMaster(publisher, hls, basePath, roomId, startedEgressIds, defaultEgressId);
            UrlValidator.validateHlsUrl(hlsUrl);
            log.info("HLS Egress 시작: roomId={}, abrEnabled={}, defaultEgressId={}, hlsUrl={}",
                    roomId, abrEnabled, defaultEgressId, hlsUrl);

            // egressId 는 대표(720p) 1건만 보존 — 중단은 room 기준 일괄 처리(stopHlsEgress)라 전 화질 ID 저장 불필요.
            return new HlsEgressResult(defaultEgressId, hlsUrl);
        } catch (RuntimeException e) {
            // 일부 화질만 시작된 상태에서 실패 → 추적한 egressId를 직접 중단(전파 지연 회피) 후, listEgress로 누락분 보강.
            log.error("HLS Egress 다중 화질 시작 실패 → 시작분 {}건 보상 중단: roomId={}", startedEgressIds.size(), roomId, e);
            startedEgressIds.forEach(egressId -> safeStopEgress(roomId, egressId));
            stopAllRoomEgress(roomId);
            // 인프라 예외(예: URL 검증 IllegalArgumentException)는 도메인 예외로 번역해 누출 방지(AGENTS Errors 규칙).
            if (e instanceof LiveMediaException) {
                throw e;
            }
            throw new LiveMediaException("HLS Egress 시작 실패: " + roomId, e);
        }
    }

    /**
     * 업로더가 있으면 master.m3u8을 게시하고 master URL을 반환한다.
     * 업로더 미등록(링크 전)이거나 업로드 실패 시 기본 화질 variant URL로 강등한다(방송은 정상, ABR만 비활성).
     * 업로드 실패 시에는 어차피 참조되지 않을 비기본 화질 egress를 중단해 잔여 비용(3배 인코딩)을 막는다.
     */
    private String publishMaster(MasterPlaylistPublisher publisher, LiveKitProperties.Hls hls, String basePath,
                                 UUID roomId, List<String> startedEgressIds, String defaultEgressId) {
        if (publisher != null) {
            try {
                String masterKey = basePath + "master.m3u8";
                publisher.publish(masterKey, MasterPlaylistGenerator.generate());
                return hls.cdnBaseUrl() + "/" + masterKey;
            } catch (RuntimeException e) {
                // master 업로드 실패 → ABR 불가. 참조되지 않을 비기본 화질 egress를 중단하고 720p로 강등(방송은 정상).
                log.warn("master.m3u8 업로드 실패 → 비기본 화질 egress 중단 + 720p 강등: basePath={}", basePath, e);
                stopNonDefaultEgresses(roomId, startedEgressIds, defaultEgressId);
            }
        }
        return hls.cdnBaseUrl() + "/" + basePath + DEFAULT_RENDITION.variantPlaylistPath();
    }

    /** 기본 화질을 제외한 시작분 egress를 best-effort 중단(업로드 실패로 ABR 강등 시 잔여 비용 차단). */
    private void stopNonDefaultEgresses(UUID roomId, List<String> startedEgressIds, String defaultEgressId) {
        for (String egressId : startedEgressIds) {
            if (!egressId.equals(defaultEgressId)) {
                safeStopEgress(roomId, egressId);
            }
        }
    }

    /**
     * 단일 화질 egress 시작. preset 또는 customOptions 중 정확히 하나만 비-null로 전달한다.
     */
    private String startRenditionEgress(
            UUID roomId, S3Upload s3Upload, String renditionPath,
            EncodingOptionsPreset preset, EncodingOptions customOptions, int segmentDuration) {

        // 안전 형태: filename_prefix·playlist_name 모두에 화질 경로를 포함한다.
        // LiveKit egress가 StorageDir을 playlist_name 디렉터리에서 유도하든(같은 디렉터리는 dedupe) 두 필드를
        // 독립으로 보든, 어느 해석에서도 세그먼트·인덱스가 동일하게 {basePath}{rendition}/ 아래에 떨어져 안전하다.
        // (bare "segment_"는 한 해석에서만 동작 → egress 통합 테스트로 확정 전까지 이 형태 유지.)
        SegmentedFileOutput hlsOutput = SegmentedFileOutput.newBuilder()
                .setProtocol(SegmentedFileProtocol.HLS_PROTOCOL)
                .setFilenamePrefix(renditionPath + "segment_")
                .setPlaylistName(renditionPath + "index.m3u8")
                .setLivePlaylistName(renditionPath + "index.m3u8")
                .setSegmentDuration(segmentDuration)
                .setS3(s3Upload)
                .build();

        try {
            Call<EgressInfo> call = egressServiceClient.startRoomCompositeEgress(
                    roomId.toString(),
                    hlsOutput,
                    LAYOUT,
                    preset,
                    customOptions,
                    false,
                    false,
                    "" //custom base url
                    , AudioMixing.DEFAULT_MIXING
            );

            Response<EgressInfo> response = call.execute();

            if (!response.isSuccessful() || response.body() == null) {
                log.error("LiveKit HLS Egress API 호출 실패: roomId={}, path={}, code={}, message={}",
                        roomId, renditionPath, response.code(), response.message());
                throw new LiveMediaException("HLS Egress 요청에 실패했습니다: " + renditionPath);
            }

            String egressId = response.body().getEgressId();
            log.info("HLS Egress 화질 시작: roomId={}, path={}, egressId={}", roomId, renditionPath, egressId);
            return egressId;

        } catch (IOException e) {
            log.error("LiveKit HLS Egress 통신 중 에러 발생: roomId={}, path={}", roomId, renditionPath, e);
            throw new LiveMediaException("HLS Egress 시작 중 통신 오류: " + roomId + " " + renditionPath, e);
        }
    }

    /** LiveKit 프리셋이 있는 화질은 프리셋을, 없는 화질(360p)은 null을 반환(→ customEncodingFor 사용). */
    private EncodingOptionsPreset presetFor(HlsRendition rendition) {
        return switch (rendition) {
            case P1080 -> EncodingOptionsPreset.H264_1080P_30;
            case P720 -> EncodingOptionsPreset.H264_720P_30;
            case P360 -> null;
        };
    }

    /** 프리셋이 없는 화질만 커스텀 EncodingOptions를 구성(현재 360p). 프리셋 화질은 null. */
    private EncodingOptions customEncodingFor(HlsRendition rendition) {
        return switch (rendition) {
            case P1080, P720 -> null;
            case P360 -> EncodingOptions.newBuilder()
                    .setWidth(rendition.getWidth())
                    .setHeight(rendition.getHeight())
                    .setFramerate(rendition.getFramerate())
                    .setVideoBitrate(rendition.getVideoBitrateKbps())
                    .setAudioBitrate(rendition.getAudioBitrateKbps())
                    .build();
        };
    }

    /**
     * s3 기록 종료. 한 방송은 1080p/720p/360p egress가 동시에 떠 있으므로,
     * room의 active egress를 모두 조회해 일괄 중단한다(고아 egress 정리 포함).
     * egressId 파라미터는 포트 호환을 위해 유지하나 단건 중단에는 사용하지 않는다.
     */
    @Override
    public void stopHlsEgress(UUID roomId, String egressId){
        stopAllRoomEgress(roomId);
    }

    private void stopAllRoomEgress(UUID roomId){
        try {
            List<EgressInfo> egresses = egressServiceClient.listEgress(roomId.toString()).execute().body();
            if (egresses == null || egresses.isEmpty()) {
                log.info("중단할 egress 없음: roomId={}", roomId);
                return;
            }
            for (EgressInfo egress : egresses) {
                if (isStoppable(egress.getStatus())) {
                    safeStopEgress(roomId, egress.getEgressId());
                }
            }
        } catch (Exception e) {
            // listEgress 자체 실패 → 이 방의 egress가 하나도 정리되지 않아 전부 고아가 됨(비용 누수).
            // 종료 트랜잭션은 막지 않되(UX), 알람이 잡도록 error로 올린다. 복구는 reconciliation 배치의 몫.
            log.error("HLS Egress 일괄 중단 실패 — 고아 egress 가능, 수동/배치 복구 필요: roomId={}", roomId, e);
        }
    }

    private boolean isStoppable(EgressStatus status){
        // ENDING/COMPLETE/FAILED/ABORTED 등은 이미 멈췄거나 멈추는 중 — STARTING/ACTIVE만 우리가 중단한다.
        return status == EgressStatus.EGRESS_STARTING
                || status == EgressStatus.EGRESS_ACTIVE;
    }

    private void safeStopEgress(UUID roomId, String egressId){
        try {
            egressServiceClient.stopEgress(egressId).execute();
            log.info("HLS Egress 중단: roomId={}, egressId={}", roomId, egressId);
        } catch (Exception e) {
            log.warn("HLS Egress 중단 실패 (이미 중단됐을 수 있음): roomId={}, egressId={}", roomId, egressId, e);
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

    @Override
    public String getSfuUrl(){
        return liveKitProperties.host();
    }
}
