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
    /** 방에 묶인 RTMP ingress 를 모두 삭제한다(종료 정리). 방 기준 일괄이라 double-prepare 고아도 함께 정리된다. */
    void deleteIngress(UUID roomId);
    void closeRoom(String sfuRoomId);
    String getSfuUrl();
}
