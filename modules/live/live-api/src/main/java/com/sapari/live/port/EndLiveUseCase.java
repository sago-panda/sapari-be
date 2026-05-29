package com.sapari.live.port;

import com.sapari.live.command.EndLiveCommand;

public interface EndLiveUseCase {
    void end(EndLiveCommand endLiveCommand);
}
