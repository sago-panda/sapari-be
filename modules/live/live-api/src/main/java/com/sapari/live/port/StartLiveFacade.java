package com.sapari.live.port;

import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.view.StartLiveResult;

public interface StartLiveFacade {
    StartLiveResult execute(StartLiveCommand command);
}
