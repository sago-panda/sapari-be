package com.sapari.live.port;

import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.view.CreateLiveView;

public interface CreateLiveFacade {
    CreateLiveView execute(CreateLiveCommand command);
}

