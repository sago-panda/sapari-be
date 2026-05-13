package com.sapari.live.application.port;

import java.util.UUID;

public interface LiveMediaManager {
    SfuRoomResult createRoom(UUID roomId);
    String issueSellerToken(UUID roomId, UUID sellerId);
    HlsEgressResult startHlsEgress(UUID roomId);
    void stopHlsEgress(UUID roomId, String egressId);
    void closeRoom(String sfuRoomId);
    String getSfuUrl();
}
