package com.sapari.live.port;

import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.view.CreateLiveView;

public interface CreateLiveUseCase {
    CreateLiveView create(CreateLiveCommand command);
}

