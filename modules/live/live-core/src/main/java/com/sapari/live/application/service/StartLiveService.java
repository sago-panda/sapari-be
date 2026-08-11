package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.command.StartLiveCommand.ProductEntry;
import com.sapari.live.domain.exception.BroadcastStartException;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveProduct;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveProductRepository;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.StartLiveUseCase;
import com.sapari.live.view.StartLiveView;

@Slf4j
@Service
@RequiredArgsConstructor
public class StartLiveService implements StartLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveProductRepository liveProductRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public StartLiveView start(StartLiveCommand command) {
        LiveRoom room = liveRoomRepository.findByIdAndSellerIdForUpdate(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 외부 호출 전 상태 사전 검증 (도메인 모델에서도 검증하나, 미디어 서버 호출을 막기 위해 여기서도 체크)
        if (!room.canStartLive()) {
            throw new InvalidLiveStateException(room.id().toString());
        }

        //pin 개수 검증, 1개가 아닌 경우 방송 시작 불가
        validatePinnedProduct(command.products());

        // 송출 방식 가드: RTMP는 시청 토큰 대신 ingress 로 push 하므로 별도 흐름(시작 대기 + webhook 전이)을 탄다.
        StartLiveView view = room.isRtmp() ? startRtmp(command, room) : startWebRtc(command, room);

        // 상품 등록은 송출 방식과 무관하게 방송 시작 시 함께 저장한다(RTMP도 시작 버튼에서 상품을 받는다).
        // 전이·미디어 처리 이후에 저장 — 같은 트랜잭션이라 원자적이고, 사전 가드 실패 시 상품 검증이 앞서지 않는다.
        saveProducts(command);
        return view;
    }

    /** WebRTC 방송 시작 — 셀러 토큰 발급 + egress 시작 + Live 전이(기존 흐름). */
    private StartLiveView startWebRtc(StartLiveCommand command, LiveRoom room) {
        // 판매자가 방송을 송출하기 위한 SFU 토큰 발급
        String sfuToken = liveMediaManager.issueSellerToken(command.roomId(), command.sellerId());
        log.info("sfuToken 발급 완료. sellerId={}", command.sellerId());

        HlsEgressResult egressResult = startEgressWithCompensation(command.roomId());

        // liveRoomEntity status -> Live 변환
        StreamInfo streamInfo = StreamInfo.of(room.sfuRoomId(), egressResult.egressId(), egressResult.hlsUrl());
        LiveRoom updatedRoom = room.startLive(streamInfo, timeProvider.now());

        //TODO: 도메인 이벤트 발행하여 연결된 시청자에게 방송 시작 이벤트 전송

        liveRoomRepository.save(updatedRoom);
        return updatedRoom.toStartLiveResult(sfuToken, liveMediaManager.getSfuUrl());
    }

    /**
     * RTMP 방송 시작 — 상품만 등록하고 시작 대기(Ready)로 둔다. 실제 Live 전이는 OBS 가 ingress 에 연결되는 시점이다.
     * 판매자가 시작을 누른 순간 이미 OBS 가 붙어 있으면(랑데부) 곧바로 egress + Live 로 마무리하고, 아직 미연결이면
     * Ready 로 저장해 ingress_started webhook 이 전이를 이어받게 한다(순서 무관 — 나중에 오는 쪽이 전이를 트리거).
     */
    private StartLiveView startRtmp(StartLiveCommand command, LiveRoom room) {
        LiveRoom armed = room.arm(timeProvider.now());

        // 방이 인정하는 ingress 가 송출 중일 때만 승격한다. "아무 ingress 나 송출 중"으로 판정하면 경합 패자
        // ingress 로도 Live 가 되는데, 그 ingress 는 고아 미디어 잡이 회수하므로 우리가 곧 끊을 방송을
        // 시작시키는 꼴이 된다. webhook(GoLiveByRtmpService)·Ready 고착 배치와 같은 대조다 — 세 경로가
        // 달라지면 도착 순서만으로 같은 상황의 결과가 갈린다(RTMP 랑데부 계약 위반).
        boolean ownIngressPublishing = liveMediaManager.publishingIngressIdsOrEmpty(command.roomId())
                .stream()
                .anyMatch(armed::hasIngress);

        if (ownIngressPublishing) {
            HlsEgressResult egressResult = startEgressWithCompensation(command.roomId());
            StreamInfo streamInfo = StreamInfo.of(room.sfuRoomId(), egressResult.egressId(), egressResult.hlsUrl());
            LiveRoom liveRoom = armed.goLiveFromReady(streamInfo, timeProvider.now());
            liveRoomRepository.save(liveRoom);
            log.info("RTMP 방송 시작 — 시작 시점 ingress 활성 확인, 즉시 Live 전이. roomId={}", command.roomId());
            // RTMP는 셀러 토큰을 쓰지 않는다(ingress push). sfuToken/sfuUrl 은 미사용이라 null.
            return new StartLiveView(liveRoom.id().toString(), null, liveRoom.hlsUrl(), null);
        }

        liveRoomRepository.save(armed);
        log.info("RTMP 방송 시작 — OBS 미연결, 시작 대기(Ready)로 저장. roomId={}", command.roomId());
        return new StartLiveView(armed.id().toString(), null, null, null);
    }

    /**
     * HLS Egress 시작 + 롤백 보상 훅 등록. egress 를 시작하기 전에 보상 훅을 등록할 수 있는지(트랜잭션 동기화) 확인해
     * tx 없이 호출되는 회귀가 생겨도 고아 egress 를 만들지 않는다.
     */
    private HlsEgressResult startEgressWithCompensation(UUID roomId) {
        // 사전 가드: 보상 훅을 등록할 수 없는 상태면 egress를 시작하기 전에 실패시킨다
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new BroadcastStartException("egress 보상 훅 등록 불가 — 트랜잭션 동기화 비활성");
        }
        // 방송자가 publish하면 자동으로 S3에 세그먼트 기록. egressId는 나중에 중단할 때 필요.
        HlsEgressResult egressResult = liveMediaManager.startHlsEgress(roomId);
        // 보상 훅은 egress 시작 바로 다음 줄에 등록 — 이 사이에 실패 가능한 코드를 끼워넣지 말 것
        EgressRollbackCompensation.register(liveMediaManager, roomId, egressResult.egressId());
        return egressResult;
    }

    /** liveProduct 도메인모델 list로 전환하여 전체 저장. sort_order 는 입력 순서, pinnedAt 은 핀 상품만 세팅. */
    private void saveProducts(StartLiveCommand command) {
        List<ProductEntry> entries = command.products();
        List<LiveProduct> products = IntStream.range(0, entries.size())
                .mapToObj(i -> {
                    ProductEntry p = entries.get(i);
                    return LiveProduct.create(command.roomId(), p.productId(), p.originalPrice(),
                            p.discountPrice(), p.liveDiscountPrice(), p.isPinned(),
                            i, p.isPinned() ? timeProvider.now() : null);
                })
                .toList();
        liveProductRepository.saveAll(products);
    }

    /**
     * 라이브 시작할 때 등록된 라이브 상품 중 pin 상품이 없거나
     * pin 상품이 의도와 다르게 여러개가 들어온 경우 에러 처리
     */
    private void validatePinnedProduct(List<ProductEntry> products) {
        long pinnedCount = products.stream().filter(ProductEntry::isPinned).count();
        if (pinnedCount != 1) {
            throw new InvalidLiveStateException("핀 상품은 정확히 1개여야 합니다. 현재 핀 상품 수: " + pinnedCount);
        }
    }
}
