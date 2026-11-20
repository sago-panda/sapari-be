package com.sapari.live.port;

import java.util.UUID;

import com.sapari.common.page.CursorPage;
import com.sapari.live.command.GetSellerRoomsCommand;
import com.sapari.live.view.SellerLiveRoomCountsView;
import com.sapari.live.view.SellerLiveRoomSummary;

public interface GetSellerRoomsUseCase {
    CursorPage<SellerLiveRoomSummary> getRooms(GetSellerRoomsCommand command);

    SellerLiveRoomCountsView getCounts(UUID sellerId);
}
