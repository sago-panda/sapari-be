package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.command.StartLiveCommand.ProductEntry;
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
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 외부 호출 전 상태 사전 검증 (도메인 모델에서도 검증하나, 미디어 서버 호출을 막기 위해 여기서도 체크)
        if (!room.canStartLive()) {
            throw new InvalidLiveStateException(room.id().toString());
        }

        //pin 개수 검증, 1개가 아닌 경우 방송 시작 불가
        validatePinnedProduct(command.products());

        // 판매자가 방송을 송출하기 위한 SFU 토큰 발급
        String sfuToken = liveMediaManager.issueSellerToken(command.roomId(), command.sellerId());
        log.info("sfuToken 발급 완료. sellerId={}", command.sellerId());

        // 사전 가드: 보상 훅을 등록할 수 없는 상태면 egress를 시작하기 전에 실패시킨다
        // (tx 없이 호출되는 회귀가 생겨도 고아 egress를 만들지 않음)
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("egress 보상 훅 등록 불가 — 트랜잭션 동기화 비활성");
        }

        // HLS Egress 시작
        // 방송자가 publish하면 자동으로 S3에 세그먼트 기록
        // egressId는 나중에 중단할 때 필요
        HlsEgressResult egressResult = liveMediaManager.startHlsEgress(command.roomId());
        // 보상 훅은 egress 시작 바로 다음 줄에 등록 — 이 사이에 실패 가능한 코드를 끼워넣지 말 것
        registerEgressCompensation(command.roomId(), egressResult.egressId());

        // liveRoomEntity status -> Live 변환
        StreamInfo streamInfo = StreamInfo.of(room.sfuRoomId(), egressResult.egressId(), egressResult.hlsUrl());
        LiveRoom updatedRoom = room.startLive(streamInfo, timeProvider.now());

        //TODO: 도메인 이벤트 발행하여 연결된 시청자에게 방송 시작 이벤트 전송

        liveRoomRepository.save(updatedRoom);

        //liveProduct 도메인모델 list로 전환하여 전체 저장
        List<LiveProduct> products = command.products().stream()
                .map(p -> LiveProduct.create(command.roomId(), p.productId(), p.originalPrice(),
                        p.discountPrice(), p.liveDiscountPrice(), p.isPinned()))
                .toList();
        liveProductRepository.saveAll(products);

        return updatedRoom.toStartLiveResult(sfuToken, liveMediaManager.getSfuUrl());
    }

    /**
     * 트랜잭션이 롤백으로 끝나면 시작해둔 HLS egress를 중단하는 보상 훅.
     * 본문 예외뿐 아니라 커밋 시점 flush 실패(write-behind INSERT 실패)까지 커버한다.
     */
    private void registerEgressCompensation(UUID roomId, String egressId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 이 콜백은 트랜잭션/커넥션이 끝난 뒤 실행됨 — 미디어 포트 호출만 허용,
                // repository/EntityManager 접근 금지
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    safeStopEgress(roomId, egressId);
                }
                // STATUS_UNKNOWN은 보상하지 않는다 — 커밋됐을 수도 있는 방송의 egress를
                // 끊으면 안 되므로, 이 케이스는 reconciliation(배치)의 몫으로 남긴다.
            }
        });
    }

    private void safeStopEgress(UUID roomId, String egressId) {
        try {
            liveMediaManager.stopHlsEgress(roomId, egressId);
            log.warn("방송 시작 롤백 → egress 보상 중단 완료. roomId={}, egressId={}", roomId, egressId);
        } catch (RuntimeException e) {
            // [의도된 swallow] afterCompletion은 트랜잭션 종료 후 실행되므로 여기서 재던져도
            // 호출자에게 전파되지 않고, 원본 롤백 원인만 가린다. 고아 egress는 로그/알람으로 추적.
            log.error("egress 보상 중단 실패 — 고아 egress 발생. roomId={}, egressId={}", roomId, egressId, e);
        }
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
