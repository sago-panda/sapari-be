package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.SfuRoomResult;
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.CreateLiveUseCase;
import com.sapari.live.view.CreateLiveView;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateLiveService implements CreateLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;
    private final ThumbnailUrlRule thumbnailUrlRule;

    @Override
    @Transactional
    public CreateLiveView create(CreateLiveCommand command){
        // 순서: 썸네일(LIVE-016) → 도메인 필드(예정 시각 LIVE-015) → 저장 → SFU. 거부는 저장·미디어 호출 전에 끝난다.
        thumbnailUrlRule.verify(command.thumbnailUrl(), command.sellerId());

        LiveRoom room = LiveRoom.create(
                command.sellerId(),
                command.title(),
                command.description(),
                command.sellerNickname(),
                command.thumbnailUrl(),
                command.scheduledAt(),
                timeProvider.now()
        );

        LiveRoom saved = liveRoomRepository.save(room);

        SfuRoomResult sfu = liveMediaManager.createRoom(saved.id());

        LiveRoom withSfu = saved.withSfuRoomId(sfu.sfuRoomId());

        liveRoomRepository.save(withSfu);

        return withSfu.toCreateLiveView();
    }
}
