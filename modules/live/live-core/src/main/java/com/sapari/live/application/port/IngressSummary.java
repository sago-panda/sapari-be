package com.sapari.live.application.port;

public record IngressSummary(
        String ingressId,
        String roomName,
        boolean publishing
) {
}
