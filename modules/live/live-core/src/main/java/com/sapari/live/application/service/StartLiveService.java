package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.StartLiveFacade;
import com.sapari.live.view.StartLiveResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class StartLiveService implements StartLiveFacade {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public StartLiveResult execute(StartLiveCommand command){
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // Live로 넘어갈 수 있는 상태인지 확인
        if(!room.canStartLive()){
            throw new InvalidLiveStateException(room.id().toString());
        }

        // 판매자가 방송을 송출하기 위한 SFU 토큰 발급
        String sfuToken = liveMediaManager.issueSellerToken(command.roomId(), command.sellerId());
        log.warn("판매자: {}, sfuToken 발급: {}", command.sellerId().toString(), sfuToken);

        // HLS Egress 시작
        // 방송자가 publish하면 자동으로 S3에 세그먼트 기록
        // egressId는 나중에 중단할 때 필요
        HlsEgressResult egressResult = liveMediaManager.startHlsEgress(command.roomId());

        // liveRoomEntity status -> Live 변환
        StreamInfo streamInfo = StreamInfo.of(room.sfuRoomId(), egressResult.egressId(), egressResult.hlsUrl());
        LiveRoom updatedRoom = room.startLive(streamInfo, timeProvider.now());

        //TODO: 도메인 이벤트 발행하여 연결된 시청자에게 방송 시작 이벤트 전송

        //저장
        liveRoomRepository.save(updatedRoom);

        return updatedRoom.toStartLiveResult(sfuToken, liveMediaManager.getSfuUrl());
    }
}
