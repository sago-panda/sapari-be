package com.sapari.live.port;

import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.view.StartLiveResult;

public interface StartLiveUseCase {
    StartLiveResult start(StartLiveCommand command);
}
