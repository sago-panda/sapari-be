package com.sapari.live.view;

import java.util.UUID;

public record CreateLiveView(
        UUID roomId,
        String title,
        String description
) {
}
