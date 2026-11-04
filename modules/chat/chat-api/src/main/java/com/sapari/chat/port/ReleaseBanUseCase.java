package com.sapari.chat.port;

import com.sapari.chat.command.ReleaseBanCommand;

public interface ReleaseBanUseCase {

    /**
     * 플랫폼 밴을 푼다 — 정본 행 삭제 + 사면 기준선 + 미러 삭제. blocking — live-app(MVC)에서 호출.
     *
     * <p><b>멱등이다.</b> 풀 밴이 없어도 성공으로 끝난다. 그 성질에 기대는 곳이 있다 — 정본은
     * 지워졌는데 미러 삭제가 실패한 뒤의 재시도다. 거기서 "풀 밴이 없다" 로 거부하면 미러에 도달할
     * 코드가 영영 안 남고, 영구 밴이면 사람이 Redis를 직접 만지기 전까지 풀리지 않는다.
     */
    void release(ReleaseBanCommand command);
}
