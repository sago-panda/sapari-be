package com.sapari.live.port;

import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.view.EnterLiveResult;

public interface EnterLiveFacade {
    EnterLiveResult enter(EnterLiveCommand command);
}
