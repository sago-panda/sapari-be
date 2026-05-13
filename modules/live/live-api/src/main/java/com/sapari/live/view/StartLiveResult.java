package com.sapari.live.view;

public record StartLiveResult(
        String roomId,
        String sfuToken,
        String hlsUrl,
        String sfuUrl
) {
}
