package com.sapari.live.infrastructure.media;

import io.livekit.server.AccessToken;
import io.livekit.server.AudioMixing;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.IngressServiceClient;
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
import livekit.LivekitIngress.IngressInfo;
import livekit.LivekitIngress.IngressInput;
import livekit.LivekitIngress.IngressState;
import livekit.LivekitModels.Room;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.IngressSummary;
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
    private final IngressServiceClient ingressServiceClient;
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
     * RTMP Ingress 발급 — OBS/전문 인코더가 push 할 수 있는 rtmpUrl·streamKey 를 생성한다.
     * ingress 는 roomId 기준으로 방에 묶이며(입력 시 방 자동 생성), 판별자 identity 는 sellerId 로 둔다.
     * streamKey 는 자격증명이라 로깅하지 않고 {@link IngressResult}로 1회 반환한다(재조회는 listIngress).
     */
    @Override
    public IngressResult createIngress(UUID roomId, UUID sellerId){
        try {
            Response<IngressInfo> response = ingressServiceClient.createIngress(
                    "rtmp-" + roomId,      // name — 대시보드 식별용 라벨(자유 문자열), 방 바인딩은 roomName 이 담당
                    roomId.toString(),     // roomName — 이 ingress 가 publish 할 방
                    sellerId.toString(),   // participantIdentity
                    sellerId.toString(),   // participantName
                    IngressInput.RTMP_INPUT
            ).execute();

            if (!response.isSuccessful() || response.body() == null) {
                log.error("LiveKit Ingress 생성 실패: roomId={}, code={}, message={}",
                        roomId, response.code(), response.message());
                throw new LiveMediaException("RTMP Ingress 생성에 실패했습니다: " + roomId);
            }

            IngressInfo info = response.body();
            // streamKey 는 자격증명 — 로그에 남기지 않는다(ingressId/url 만 기록).
            log.info("RTMP Ingress 생성: roomId={}, ingressId={}", roomId, info.getIngressId());
            return new IngressResult(info.getIngressId(), info.getUrl(), info.getStreamKey());
        } catch (IOException e) {
            log.error("LiveKit Ingress 생성 통신 오류: roomId={}", roomId, e);
            throw new LiveMediaException("RTMP Ingress 생성 중 통신 오류: " + roomId, e);
        }
    }

    /**
     * 방의 RTMP ingress 가 실제 송출 중(OBS 연결·publish)인지 확인한다.
     * 시작 시점 랑데부(판매자가 방송 시작을 누른 순간 OBS가 이미 붙어 있는 경우)를 판정하는 데 쓴다.
     * roomName(=roomId)으로 필터해 조회하며, 상태가 PUBLISHING 인 ingress 가 하나라도 있으면 활성으로 본다.
     *
     * <p>조회 실패 시 false 를 반환한다(fail-safe — 활성으로 단정해 무단 Live 전이를 만들지 않음). 보통은 곧
     * 도착할 {@code ingress_started} webhook 이 전이를 이어받지만, "OBS 가 시작 이전에 이미 연결(그때 webhook 은
     * 방이 아직 Scheduled 라 no-op) + 마침 이 조회마저 실패"인 드문 경우엔 재전송될 이벤트가 없어 방이 Ready 로
     * 남을 수 있다 — 이는 orphan reconciliation 배치의 몫(미구현 follow-up 버킷).
     */
    @Override
    public boolean isIngressActive(UUID roomId){
        try {
            List<IngressInfo> ingresses = ingressServiceClient.listIngress(roomId.toString()).execute().body();
            if (ingresses == null || ingresses.isEmpty()) {
                return false;
            }
            return ingresses.stream().anyMatch(this::isPublishing);
        } catch (Exception e) {
            // 조회 실패 시 활성으로 단정하지 않고 false. 보통은 후속 ingress_started webhook 이 전이를 이어받으나,
            // OBS 선연결 + 조회 실패가 겹치면 재전송 이벤트가 없어 Ready 로 남을 수 있음(reconciliation 대상).
            log.warn("RTMP ingress 활성 조회 실패 — 비활성으로 간주: roomId={}", roomId, e);
            return false;
        }
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
            stopHlsEgress(roomId);
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
     */
    @Override
    public void stopHlsEgress(UUID roomId){
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
     * 방에 묶인 RTMP ingress 일괄 삭제(종료 정리). ingressId 단건이 아니라 roomName(=roomId) 조회로 전부 지워
     * double-prepare 로 생긴 고아 ingress 도 함께 정리한다(stopHlsEgress 와 동일 패턴).
     * best-effort — 실패해도 종료 트랜잭션은 막지 않으며, 잔여 고아는 reconciliation 배치의 몫.
     */
    @Override
    public void deleteIngress(UUID roomId){
        try {
            // Retrofit execute()는 HTTP 비-2xx에서 예외를 던지지 않는다 — isSuccessful 검사 없이는 실패가 은폐된다.
            Response<List<IngressInfo>> response = ingressServiceClient.listIngress(roomId.toString()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                log.error("RTMP Ingress 목록 조회 실패 — 고아 ingress 가능, 수동/배치 복구 필요: roomId={}, code={}",
                        roomId, response.code());
                return;
            }
            List<IngressInfo> ingresses = response.body();
            if (ingresses.isEmpty()) {
                log.info("삭제할 ingress 없음: roomId={}", roomId);
                return;
            }
            for (IngressInfo ingress : ingresses) {
                safeDeleteIngress(roomId, ingress.getIngressId());
            }
        } catch (Exception e) {
            // listIngress 자체 실패 → 이 방의 ingress가 정리되지 않아 고아가 됨. 알람이 잡도록 error로 올린다.
            log.error("RTMP Ingress 일괄 삭제 실패 — 고아 ingress 가능, 수동/배치 복구 필요: roomId={}", roomId, e);
        }
    }

    /**
     * ingress 단건 삭제(고아 정리 배치). 방 단위 삭제와 달리 지목한 하나만 지운다 — 배치가 지우려는 고아 옆에
     * 살아 있어야 할 ingress 가 있을 수 있다. 정리 계열이라 best-effort(실패해도 다음 회차가 다시 시도).
     */
    @Override
    public void deleteIngress(UUID roomId, String ingressId){
        safeDeleteIngress(roomId, ingressId);
    }

    private void safeDeleteIngress(UUID roomId, String ingressId){
        try {
            Response<IngressInfo> response = ingressServiceClient.deleteIngress(ingressId).execute();
            if (!response.isSuccessful()) {
                log.warn("RTMP Ingress 삭제 실패 (이미 삭제됐을 수 있음): roomId={}, ingressId={}, code={}",
                        roomId, ingressId, response.code());
                return;
            }
            log.info("RTMP Ingress 삭제: roomId={}, ingressId={}", roomId, ingressId);
        } catch (Exception e) {
            log.warn("RTMP Ingress 삭제 실패 (이미 삭제됐을 수 있음): roomId={}, ingressId={}", roomId, ingressId, e);
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

    /**
     * LiveKit 전체 ingress 목록 조회 — orphan live 정리 전용
     *
     * <p>정리 계열(deleteIngress 등)과 달리 실패를 삼키지 않고 던진다 — 빈 목록은 "고아 없음"으로
     * 읽혀 배치가 조용히 성공 종료하는 것을 막기 위함
     */
    @Override
    public List<IngressSummary> listAllIngress(){
        try{
            Response<List<IngressInfo>> response = ingressServiceClient.listIngress().execute();
            //실패판정
            if(!response.isSuccessful() || response.body() == null){
                log.error("LiveKit Ingress 전체 조회 실패: code={}, message={}", response.code(), response.message());
                throw new LiveMediaException("Ingress 전체 조회에 실패했습니다.");
            }
            //매핑해서 반환
            return response.body().stream()
                    .map(info -> new IngressSummary(
                            info.getIngressId(),
                            info.getRoomName(),
                            isPublishing(info)
                    ))
                    .toList();
        }catch (IOException e){
            log.error("LiveKit Ingress 전체 조회 통신 오류", e);
            throw new LiveMediaException("Ingress 전체 조회 중 통신 오류", e);
        }
    }

    /**
     * LiveKit 전체 egress 목록 조회 — orphan live 정리 전용
     *
     *  <p>{@link #listAllIngress()}와 같은 이유로 실패를 삼키지 않는다.
     */
    @Override
    public List<EgressSummary> listAllEgress(){
        try{
            Response<List<EgressInfo>> response = egressServiceClient.listEgress().execute();

            if(!response.isSuccessful() || response.body() == null){
                log.error("LiveKit Egress 전체 조회 실패: code={}, message={}", response.code(), response.message());
                throw new LiveMediaException("Egress 전체 조회에 실패했습니다.");
            }

            return response.body().stream()
                    .map(info -> new EgressSummary(
                            info.getEgressId(),
                            info.getRoomName(),
                            isStoppable(info.getStatus()),
                            toInstant(info.getStartedAt())
                    ))
                    .toList();

        }catch (IOException e){
            log.error("LiveKit Egress 전체 조회 통신 오류", e);
            throw new LiveMediaException("Egress 전체 조회 중 통신 오류", e);
        }
    }

    private boolean isPublishing(IngressInfo info){
        return info.hasState()
                && info.getState().getStatus() == IngressState.Status.ENDPOINT_PUBLISHING;
    }

    /**
     * LiveKit 의 unix 나노초를 Instant 로. 0(아직 시작 전)은 null 로 둔다 —
     * 그대로 변환하면 1970 이 되어 고아 판정 유예 시간을 항상 지난 것으로 만든다.
     */
    private Instant toInstant(long nanos){
        if(nanos == 0){
            return null;
        }
        return Instant.ofEpochSecond(0, nanos);
    }
}
