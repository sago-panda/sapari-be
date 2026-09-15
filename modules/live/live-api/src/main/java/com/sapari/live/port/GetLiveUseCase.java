package com.sapari.live.port;

import com.sapari.live.command.GetLiveCommand;
import com.sapari.live.view.GetLiveView;

public interface GetLiveUseCase {

    GetLiveView getRooms(GetLiveCommand command);
}
