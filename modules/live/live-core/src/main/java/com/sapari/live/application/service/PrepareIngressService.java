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
    private final RtmpIngressAssigner rtmpIngressAssigner;
    private final TimeProvider timeProvider;

    // @Transactional 없음(의도): createIngress(외부 I/O)를 트랜잭션 밖에서 호출해 네트워크 왕복 동안
    // DB 커넥션·행 잠금을 점유하지 않는다(루트 AGENTS "Minimize external calls inside a transaction").
    // 그 대가로 아래 가드가 스냅샷 검사라 배타적이지 않으므로, 배정은 조건부 UPDATE 한 문장
    // (RtmpIngressAssigner)으로 하고 진 쪽이 자기 ingress 를 회수한다.
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

        // 배정은 조건부 UPDATE 한 문장 — 위 가드는 스냅샷이라 동시 요청이 둘 다 통과할 수 있고,
        // 그러면 각자 ingress 를 만들어 판매자에게 유효한 streamKey 가 여러 개 나간다.
        boolean assigned = rtmpIngressAssigner.assignIfAbsent(
                command.roomId(), command.sellerId(), result.ingressId(), timeProvider.now());
        if (!assigned) {
            // 진 쪽은 자기가 만든 ingress 를 즉시 회수한다(단건 삭제 — 방 단위로 지우면 이긴 쪽 것까지 날아간다).
            // 이 삭제가 실패해도 고아 미디어 정리 잡이 회수하므로 여기서 더 다루지 않는다.
            liveMediaManager.deleteIngress(command.roomId(), result.ingressId());
            // UPDATE 0건은 "경합에서 짐"과 "그새 상태가 바뀜"을 구분하지 못한다 — 둘 다 정상 거부지만
            // 빈도가 다르므로(전자는 판매자 더블클릭, 후자는 드묾) 로그에 남겨 구분 가능하게 둔다.
            log.info("RTMP ingress 배정 실패(경합 패배 또는 상태 변경) — 생성한 ingress 회수. roomId={}, ingressId={}",
                    command.roomId(), result.ingressId());
            throw new InvalidLiveStateException("이미 RTMP ingress 가 발급된 방입니다: " + command.roomId());
        }
        log.info("RTMP ingress 발급 완료. roomId={}, ingressId={}", room.id(), result.ingressId());

        return new IngressCredentialView(result.ingressId(), result.rtmpUrl(), result.streamKey());
    }
}
