package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.PrepareIngressCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.PrepareIngressUseCase;
import com.sapari.live.view.IngressCredentialView;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareIngressService implements PrepareIngressUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;

    // @Transactional 없음(의도): createIngress(외부 I/O)를 트랜잭션 밖에서 호출해 네트워크 왕복 동안
    // DB 커넥션을 점유하지 않는다(루트 AGENTS "Minimize external calls inside a transaction").
    // egressId를 room 저장과 원자적으로 커밋해야 하는 StartLiveService와 달리, 여기선 단건 save 뿐이라
    // 원자성 요구가 없고(고아 ingress는 reconciliation에 위임), save 자체는 Spring Data가 트랜잭션을 관리한다.
    @Override
    public IngressCredentialView prepare(PrepareIngressCommand command){
        // 소유권: 판매자 본인의 방만 조회됨(남의 방에 ingress 발급 차단)
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 외부 호출 전 가드 — 방송 전(Scheduled)만 준비 허용
        if (!room.canPrepareIngress()) {
            throw new InvalidLiveStateException(room.id().toString());
        }
        // 멱등: 이미 ingress 가 발급된 방이면 재발급하지 않고 거부(중복 ingress 방지).
        // streamKey 분실 복구(재발급)는 별도 티켓의 몫.
        if (room.isRtmp()) {
            throw new InvalidLiveStateException("이미 RTMP ingress 가 발급된 방입니다: " + room.id());
        }

        IngressResult result = liveMediaManager.createIngress(command.roomId(), command.sellerId());

        // createIngress 성공 후 save 가 실패하면 ingress 가 고아가 된다(streamKey 미전달이라 유휴·무해).
        // 복구는 고아 ingress 정리(reconciliation) 배치의 몫 — 이 경로에 보상 훅은 두지 않는다.
        LiveRoom updated = room.assignRtmpIngress(result.ingressId(), timeProvider.now());
        liveRoomRepository.save(updated);
        log.info("RTMP ingress 발급 완료. roomId={}, ingressId={}", room.id(), result.ingressId());

        return new IngressCredentialView(result.ingressId(), result.rtmpUrl(), result.streamKey());
    }
}
