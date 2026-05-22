package com.sapari.live.port;

import com.sapari.live.command.EndLiveCommand;

public interface EndLiveFacade {
    void end(EndLiveCommand endLiveCommand);
}
