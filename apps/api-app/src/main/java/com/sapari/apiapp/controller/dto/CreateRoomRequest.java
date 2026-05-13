package com.sapari.apiapp.controller.dto;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.command.CreateLiveCommand;

public record CreateRoomRequest(
        String title,
        String description,
        Instant scheduledAt
) {
    public CreateLiveCommand toCommand(UUID sellerId){
        return new CreateLiveCommand(sellerId, title, description, scheduledAt);
    }
}
