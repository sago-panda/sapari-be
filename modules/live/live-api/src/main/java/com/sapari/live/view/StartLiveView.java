package com.sapari.live.view;

public record StartLiveView(
        String roomId,
        String sfuToken,
        String hlsUrl,
        String sfuUrl
) {
}
