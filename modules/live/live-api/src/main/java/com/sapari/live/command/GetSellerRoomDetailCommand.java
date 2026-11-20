package com.sapari.live.command;

import java.util.UUID;

public record GetSellerRoomDetailCommand(
        UUID roomId,
        UUID sellerId
) {
}
