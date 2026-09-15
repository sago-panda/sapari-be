package com.sapari.live.port;

import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.view.EnterLiveView;

public interface EnterLiveUseCase {
    EnterLiveView enter(EnterLiveCommand command);
}
