package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.command.GetSellerRoomDetailCommand;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetLiveRoomDetailUseCase;
import com.sapari.live.view.PublicLiveRoomDetailView;
import com.sapari.live.view.SellerLiveRoomDetailView;

/**
 * 방 상세 — 입장과 달리 룸 토큰을 발급하지 않고 모든 상태의 방을 보여 준다. DB 만 읽는다(LiveKit 호출 없음).
 */
@Service
@RequiredArgsConstructor
public class GetLiveRoomDetailService implements GetLiveRoomDetailUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public PublicLiveRoomDetailView getPublicDetail(UUID roomId) {
        LiveRoom room = liveRoomRepository.findById(roomId)
                .orElseThrow(() -> new LiveNotFoundException(roomId.toString()));
        return new PublicLiveRoomDetailView(room.title(), room.description(), room.thumbnailUrl(),
                room.sellerNickname(), LiveRoomTabs.of(room.status()), room.scheduledAt(),
                LiveRoomTabs.startedAtOf(room.status()), LiveRoomTabs.endedAtOf(room.status()),
                room.vodPublic() ? vodStatusOf(room) : null);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerLiveRoomDetailView getSellerDetail(GetSellerRoomDetailCommand command) {
        // 소유권은 쿼리에 둔다 — 남의 방과 없는 방이 같은 404 여야 존재 여부가 새지 않는다.
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));
        return new SellerLiveRoomDetailView(room.title(), room.description(), room.thumbnailUrl(),
                room.sellerNickname(), LiveRoomTabs.of(room.status()), room.scheduledAt(),
                LiveRoomTabs.startedAtOf(room.status()), LiveRoomTabs.endedAtOf(room.status()),
                vodStatusOf(room), room.rtmpUrl());
    }

    /** 공개 상세는 다시보기 비공개 방의 처리 상태를 숨긴다(GetLiveReplayService 의 vodPublic 규칙과 같은 방향). 판매자 상세는 본다. */
    private static String vodStatusOf(LiveRoom room) {
        return room.vodStatus() == null ? null : room.vodStatus().name();
    }
}
