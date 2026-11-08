package com.sapari.live.port;

import com.sapari.live.command.UpdateLiveCommand;

public interface UpdateLiveUseCase {
    void update(UpdateLiveCommand command);
}
