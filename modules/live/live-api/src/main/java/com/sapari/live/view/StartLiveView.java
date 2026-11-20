package com.sapari.live.view;

import java.util.UUID;

public record StartLiveView(
        UUID roomId,
        String sfuToken,
        String hlsUrl,
        String sfuUrl
) {
}
