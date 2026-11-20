package com.sapari.live.port;

import com.sapari.common.page.CursorPage;
import com.sapari.live.command.GetRecentLivesQuery;
import com.sapari.live.view.RecentLiveView;

/** 회원이 최근 90일 안에 본 라이브를 마지막 시청 시각 내림차순으로. */
public interface GetRecentLivesUseCase {

    CursorPage<RecentLiveView> getRecent(GetRecentLivesQuery query);
}
