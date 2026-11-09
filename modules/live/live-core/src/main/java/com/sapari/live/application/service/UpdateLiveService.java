package com.sapari.live.application.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.UpdateLiveCommand;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.UpdateLiveUseCase;

/** 예약 방의 제목·설명·썸네일·예정 시각을 바꾼다. 외부 호출이 없다. */
@Service
@RequiredArgsConstructor
public class UpdateLiveService implements UpdateLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final TimeProvider timeProvider;
    private final ThumbnailUrlRule thumbnailUrlRule;

    @Override
    @Transactional
    public void update(UpdateLiveCommand command) {
        // 잠금 조회: 시작·취소와 경합하므로 직렬화한다 — 시작된 방의 예정 시각이 바뀌면 안 된다.
        LiveRoom room = liveRoomRepository.findByIdAndSellerIdForUpdate(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 상태(LIVE-014)·예정 시각(LIVE-015)을 도메인이 먼저 판정한다 — 수정 불가 방에 썸네일 오류가 먼저 나가지 않게.
        LiveRoom updated = room.update(command.title(), command.description(), command.thumbnailUrl(),
                command.scheduledAt(), timeProvider.now());

        // 썸네일은 바꿀 때만 판정한다(LIVE-016) — 규칙 이전에 만든 방도 기존 썸네일 그대로 수정할 수 있게.
        if (!Objects.equals(command.thumbnailUrl(), room.thumbnailUrl())) {
            thumbnailUrlRule.verify(command.thumbnailUrl(), command.sellerId());
        }

        liveRoomRepository.save(updated);
    }
}
