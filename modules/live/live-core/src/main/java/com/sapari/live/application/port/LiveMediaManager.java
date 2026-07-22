package com.sapari.live.application.port;

import java.util.UUID;

public interface LiveMediaManager {
    SfuRoomResult createRoom(UUID roomId);
    String issueSellerToken(UUID roomId, UUID sellerId);
    IngressResult createIngress(UUID roomId, UUID sellerId);
    /** 해당 방의 RTMP ingress 가 실제로 송출 중인지(OBS 연결·publish 중). 시작 시점 랑데부 판정에 쓴다. */
    boolean isIngressActive(UUID roomId);
    HlsEgressResult startHlsEgress(UUID roomId);
    void stopHlsEgress(UUID roomId, String egressId);
    void closeRoom(String sfuRoomId);
    String getSfuUrl();
}
