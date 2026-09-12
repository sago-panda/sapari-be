package com.sapari.live.port;

import java.util.UUID;

import com.sapari.live.view.ReplayView;

/** 공개 다시보기 조회. 미종료 또는 아카이브가 없는 방은 다시보기 리소스가 없으므로 404이다. */
public interface GetLiveReplayUseCase {
    ReplayView getReplay(UUID roomId);
}
