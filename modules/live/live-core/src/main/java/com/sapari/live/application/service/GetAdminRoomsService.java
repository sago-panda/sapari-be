package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.common.page.OffsetPage;
import com.sapari.common.page.PageSupport;
import com.sapari.live.command.GetAdminRoomsQuery;
import com.sapari.live.domain.exception.InvalidLiveQueryException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.AdminRoomFilter;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetAdminRoomsUseCase;
import com.sapari.live.view.AdminLiveRoomSummary;

/** 관리자 전체 방 목록(SPR-212). 상태 묶음은 판매자 탭({@link LiveRoomTabs})과 같다. */
@Service
@RequiredArgsConstructor
public class GetAdminRoomsService implements GetAdminRoomsUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public OffsetPage<AdminLiveRoomSummary> getRooms(GetAdminRoomsQuery query) {
        int page = query.page() == null ? 0 : query.page();
        if (page < 0) {
            throw new InvalidLiveQueryException(LiveErrorCode.INVALID_ADMIN_ROOM_QUERY, "page 는 0 이상이어야 합니다: " + page);
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new InvalidLiveQueryException(LiveErrorCode.INVALID_ADMIN_ROOM_QUERY,
                    "from 은 to 보다 앞서야 합니다: " + query.from() + " ~ " + query.to());
        }
        int size = PageSupport.normalizeSize(query.size());
        AdminRoomFilter filter = new AdminRoomFilter(
                LiveRoomTabs.statusesOf(query.status()), query.sellerId(), query.from(), query.to());

        // 오프셋이 int 를 넘는 페이지는 어떤 행도 없다 — 조회하면 JPA 가 오프셋 변환에서 500 을 낸다.
        List<LiveRoom> rows = (long) page * size > Integer.MAX_VALUE
                ? List.of()
                : liveRoomRepository.findAdminRooms(filter, page, size);
        long total = liveRoomRepository.countAdminRooms(filter);

        return OffsetPage.of(rows.stream().map(GetAdminRoomsService::toSummary).toList(), page, size, total);
    }

    private static AdminLiveRoomSummary toSummary(LiveRoom room) {
        return new AdminLiveRoomSummary(room.id(), room.sellerId(), room.sellerNickname(), room.title(),
                room.thumbnailUrl(), LiveRoomTabs.of(room.status()), room.scheduledAt(),
                LiveRoomTabs.startedAtForSeller(room.status()), LiveRoomTabs.endedAtForSeller(room.status()));
    }
}
