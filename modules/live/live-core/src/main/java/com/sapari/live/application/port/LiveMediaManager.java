package com.sapari.live.application.port;

import java.util.List;
import java.util.UUID;

public interface LiveMediaManager {
    SfuRoomResult createRoom(UUID roomId);
    String issueSellerToken(UUID roomId, UUID sellerId);
    IngressResult createIngress(UUID roomId, UUID sellerId);
    /** 해당 방의 RTMP ingress 가 실제로 송출 중인지(OBS 연결·publish 중). 시작 시점 랑데부 판정에 쓴다. */
    boolean isIngressActive(UUID roomId);
    /**
     * {@link #isIngressActive(UUID)} 와 같은 판정이지만 <b>조회 실패 시 예외</b>다.
     *
     * <p>둘을 나눈 건 실패의 안전 방향이 반대라서다 — 시작 랑데부는 모르면 승격하지 않는 게 안전하지만(false),
     * 만료 배치는 모르면 <b>만료해 버리는</b> 게 되어 송출 중인 방의 ingress 를 지운다. 파괴적 판단의 입력으로는
     * 반드시 이쪽을 쓸 것.
     */
    boolean isPublishingOrThrow(UUID roomId);
    HlsEgressResult startHlsEgress(UUID roomId);
    /** 방의 HLS egress 를 <b>모두</b> 중단한다. 한 방송이 화질별로 여러 egress 를 띄우므로 단건 중단은 없다. */
    void stopHlsEgress(UUID roomId);
    /** 방에 묶인 RTMP ingress 를 모두 삭제한다(종료 정리). 방 기준 일괄이라 double-prepare 고아도 함께 정리된다. */
    void deleteIngress(UUID roomId);
    /**
     * ingress 하나만 삭제한다(고아 정리 배치). 같은 방에 살아 있어야 할 ingress 가 함께 있을 수 있으므로
     * 방 단위 일괄 삭제를 쓰면 안 된다. roomId 는 로그 식별용.
     */
    void deleteIngress(UUID roomId, String ingressId);
    void closeRoom(String sfuRoomId);
    String getSfuUrl();
    List<IngressSummary> listAllIngress();
    List<EgressSummary> listAllEgress();
}
