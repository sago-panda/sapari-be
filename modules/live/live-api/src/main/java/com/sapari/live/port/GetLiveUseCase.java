package com.sapari.live.port;

import com.sapari.live.command.GetLiveCommand;
import com.sapari.live.view.GetLiveResult;

public interface GetLiveUseCase {

    GetLiveResult getRooms(GetLiveCommand command);
}
