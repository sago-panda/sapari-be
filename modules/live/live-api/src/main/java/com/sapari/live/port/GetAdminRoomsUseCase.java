package com.sapari.live.port;

import com.sapari.common.page.OffsetPage;
import com.sapari.live.command.GetAdminRoomsQuery;
import com.sapari.live.view.AdminLiveRoomSummary;

/** 관리자 전체 방 목록(페이지 번호). 상태·판매자·예정 시각 기간으로 거르고, 예정 시각 최신순이다. */
public interface GetAdminRoomsUseCase {
    OffsetPage<AdminLiveRoomSummary> getRooms(GetAdminRoomsQuery query);
}
