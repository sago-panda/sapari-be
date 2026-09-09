package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.domain.exception.BroadcastStartException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * RTMP OBS 연결(ingress_started webhook) 시 시작 대기(Ready) 방을 Live 로 전이하고 HLS egress 를 시작한다.
 *
 * <p>webhook 은 판매자 식별자를 싣지 않으므로 roomId 로만 방을 찾는다(소유권은 ingress 발급·방송 시작 단계에서
 * 이미 검증됨). ingress_started 는 webhook 핸들러가 트리거하며, 여기서는 도메인 전이만 책임진다.
 *
 * <p><b>멱등</b>: {@code Ready + RTMP} 가 아니면(아직 상품 미등록 Scheduled, 이미 Live 로 전이됨, WebRTC 방 등)
 * no-op 이다. LiveKit 은 실패 시 webhook 을 재전송하고 TTL 내 리플레이도 가능하므로 같은 이벤트가 여러 번 와도
 * 안전하다. 시작 시점 랑데부({@code StartLiveService})가 이미 Live 로 올렸다면 여기서는 조용히 스킵된다.
 *
 * <p><b>동시 도착</b>도 안전하다 — 방 조회가 {@code findByIdForUpdate}(행 잠금)라 같은 방에 대한 전이는
 * 직렬화된다. 뒤늦게 락을 얻은 쪽은 이미 {@code Live} 인 방을 읽어 위 멱등 가드에서 no-op 이 되므로,
 * {@code startHlsEgress} 가 두 번 호출돼 고아 egress 가 생기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoLiveByRtmpService {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;
    private final LiveMetrics liveMetrics;

    /**
     * {@code trigger} 는 <b>어느 경로가 이 방을 올렸는지</b>를 남기기 위한 것이다. 승격 결과는 어느 쪽이든
     * Live 라 사후에 구분할 수 없는데, 판매자 체감은 즉시(webhook)와 최대 10분 지연(배치)으로 전혀 다르다.
     * 호출자가 각자 지표를 남기는 대신 파라미터로 받는 이유는 새 진입점이 생겼을 때 컴파일러가 짚어주기
     * 때문이다 — 빠뜨리면 그 경로만 조용히 통계에서 사라진다.
     *
     * <p>ingressId 대조는 <b>모든 경로에서</b> 한다 — webhook 은 이벤트가 실어 온 값을, Ready 고착 정리 배치는
     * {@code listRoomIngress} 로 확인한 값을 넘긴다. 대조를 건너뛰는 진입점을 따로 두면 그쪽만 가드가
     * 빠지고, 방이 인정하지 않은 ingress(경합 패자 잔존)가 방송을 시작시킨다.
     */
    @Transactional
    public void goLiveByRtmp(UUID roomId, String eventIngressId, PromotionTrigger trigger) {
        LiveRoom room = liveRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new LiveNotFoundException(roomId.toString()));

        // 멱등 가드: 시작 대기(Ready)이고 RTMP 인 방만 전이 대상. 그 외는 no-op (재전송/리플레이/랑데부 선처리 안전).
        if (!room.canGoLiveByRtmp()) {
            log.info("RTMP go-live 스킵 — 전이 대상 아님(Ready+RTMP 아님). roomId={}, status={}",
                    roomId, room.status().getClass().getSimpleName());
            return;
        }

        // 이벤트의 ingress 가 이 방의 것인지 대조한다. 경합에서 진 ingress 의 회수가 실패해 살아남으면
        // 그 streamKey 를 쥔 쪽이 방을 Live 로 올릴 수 있다 — roomName 만으로 전이하면 그게 통과한다.
        // hasIngress 는 null/blank 를 거짓으로 본다 — ingressInfo 없는 이벤트는 여기서 거부된다.
        if (!room.hasIngress(eventIngressId)) {
            log.warn("RTMP go-live 스킵 — 이 방의 ingress 가 아님. roomId={}, eventIngressId={}",
                    roomId, eventIngressId);
            return;
        }

        // 사전 가드: 보상 훅을 등록할 수 없는 상태면 egress를 시작하기 전에 실패시킨다(고아 egress 방지).
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new BroadcastStartException("egress 보상 훅 등록 불가 — 트랜잭션 동기화 비활성");
        }

        HlsEgressResult egressResult = liveMediaManager.startHlsEgress(roomId);
        // 보상 훅은 egress 시작 바로 다음 줄에 등록 — 이 사이에 실패 가능한 코드를 끼워넣지 말 것
        EgressRollbackCompensation.register(liveMediaManager, roomId, egressResult.egressId());

        StreamInfo streamInfo = StreamInfo.of(room.sfuRoomId(), egressResult.egressId(), egressResult.hlsUrl());
        LiveRoom liveRoom = room.goLiveFromReady(streamInfo, timeProvider.now());

        //TODO: 도메인 이벤트 발행하여 연결된 시청자에게 방송 시작 이벤트 전송

        liveRoomRepository.save(liveRoom);
        // 커밋된 전이만 세도록 구현(MicrometerLiveMetrics)이 트랜잭션 경계를 본다 — 여기서 롤백되면
        // 아래 두 건은 기록되지 않는다.
        liveMetrics.roomTransitioned(room.status(), liveRoom.status());
        liveMetrics.rtmpPromoted(trigger);
        log.info("RTMP go-live 완료 — Ready→Live 전이. roomId={}, 경로={}", roomId, trigger);
    }
}
