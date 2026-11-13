package com.sapari.live.port;

import com.sapari.common.page.CursorPage;
import com.sapari.live.command.GetReplaysQuery;
import com.sapari.live.view.ReplaySummaryView;

/**
 * 다시보기 공개 목록 — 종료됐고 녹화가 정상 완료(READY)됐으며 다시보기가 공개인 방을 최근에 끝난 순으로.
 * 비공개·녹화 미완료·실패 방은 들어가지 않는다(단건 {@link GetLiveReplayUseCase} 와 같은 기준).
 */
public interface GetReplaysUseCase {
    CursorPage<ReplaySummaryView> getReplays(GetReplaysQuery query);
}
