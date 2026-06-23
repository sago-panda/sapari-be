package com.sapari.live.port;

import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.view.StartLiveView;

public interface StartLiveUseCase {
    StartLiveView start(StartLiveCommand command);
}
