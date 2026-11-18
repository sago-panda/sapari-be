package com.sapari.live.port;

import java.util.UUID;

import com.sapari.live.command.GetSellerRoomDetailCommand;
import com.sapari.live.view.PublicLiveRoomDetailView;
import com.sapari.live.view.SellerLiveRoomDetailView;

public interface GetLiveRoomDetailUseCase {
    PublicLiveRoomDetailView getPublicDetail(UUID roomId);

    /** 방 주인만. 남의 방은 없는 방과 같은 404 다. */
    SellerLiveRoomDetailView getSellerDetail(GetSellerRoomDetailCommand command);
}
