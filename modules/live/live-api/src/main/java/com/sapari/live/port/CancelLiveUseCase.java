package com.sapari.live.port;

import com.sapari.live.command.CancelLiveCommand;

public interface CancelLiveUseCase {
    void cancel(CancelLiveCommand command);
}
