package com.sapari.live.port;

import com.sapari.common.page.CursorPage;
import com.sapari.live.command.GetUpcomingLivesQuery;
import com.sapari.live.view.UpcomingLiveView;

/**
 * 예정 방송 공개 목록 — 아직 시작하지 않은 방(예약·RTMP 준비)을 예정 시각이 이른 순으로.
 * 시작했거나 끝난 방은 들어가지 않는다.
 */
public interface GetUpcomingLivesUseCase {
    CursorPage<UpcomingLiveView> getUpcoming(GetUpcomingLivesQuery query);
}
