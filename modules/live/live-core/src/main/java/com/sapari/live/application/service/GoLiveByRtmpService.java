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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoLiveByRtmpService {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;

    @Transactional
    public void goLiveByRtmp(UUID roomId) {
        LiveRoom room = liveRoomRepository.findById(roomId)
                .orElseThrow(() -> new LiveNotFoundException(roomId.toString()));

        // 멱등 가드: 시작 대기(Ready)이고 RTMP 인 방만 전이 대상. 그 외는 no-op (재전송/리플레이/랑데부 선처리 안전).
        if (!room.canGoLiveByRtmp()) {
            log.info("RTMP go-live 스킵 — 전이 대상 아님(Ready+RTMP 아님). roomId={}, status={}",
                    roomId, room.status().getClass().getSimpleName());
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
        log.info("RTMP go-live 완료 — Ready→Live 전이. roomId={}", roomId);
    }
}
