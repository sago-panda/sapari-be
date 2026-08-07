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
     * 이 방 이름으로 등록된 ingress <b>전부</b>(각각 송출 중인지 포함). 조회 실패는 <b>예외</b>다.
     *
     * <p>{@link #isIngressActive(UUID)} 와 실패 방향이 반대라 따로 둔다 — 시작 랑데부는 모르면 승격하지 않는 게
     * 안전하지만(false), 만료 배치는 모르면 <b>만료해 버리는</b> 게 되어 송출 중인 방의 ingress 를 지운다.
     * 파괴적 판단의 입력으로는 반드시 이쪽을 쓸 것.
     *
     * <p><b>불리언이 아닌 이유</b>: 한 방에 ingress 가 여럿 살아 있을 수 있다(경합 패자의 회수 실패).
     * "송출 중인가"만 알면 <i>어느</i> ingress 인지 모른 채 승격하게 되어, 방이 인정하지 않은 ingress 가
     * 방송을 시작시킨다. 호출자는 방의 {@code ingress_id} 가 목록에 <b>포함되는지</b>로 판단해야 한다 —
     * 단건을 골라 비교하면 여럿 중 다른 하나를 집어 "송출 안 함"으로 오판하고 살아 있는 방송을 끊는다.
     *
     * <p><b>송출 중인 것만 거르지 않고 전부 주는 이유</b>: 만료 판정에는 "등록은 됐지만 송출 안 함"(정상 —
     * OBS 가 끝내 안 붙은 방)과 "LiveKit 이 이 방의 ingress 를 아예 모름"(비정상 — host/apiKey 가 다른
     * 클러스터를 가리킴)을 갈라야 한다. 송출 중인 것만 받으면 둘 다 빈 목록이라 구분이 사라지고, 오설정
     * 한 회차가 살아 있는 방송을 전부 Ended 로 만든다.
     */
    List<IngressSummary> listRoomIngress(UUID roomId);
    HlsEgressResult startHlsEgress(UUID roomId);
    /** 방의 HLS egress 를 <b>모두</b> 중단한다. 한 방송이 화질별로 여러 egress 를 띄우므로 단건 중단은 없다. */
    void stopHlsEgress(UUID roomId);
    /** 방에 묶인 RTMP ingress 를 모두 삭제한다(종료 정리). 방 기준 일괄이라 double-prepare 고아도 함께 정리된다. */
    void deleteIngress(UUID roomId);
    /**
     * ingress 하나만 삭제한다(고아 정리 배치). 같은 방에 살아 있어야 할 ingress 가 함께 있을 수 있으므로
     * 방 단위 일괄 삭제를 쓰면 안 된다.
     *
     * <p><b>roomId 는 로그 상관용일 뿐 삭제 범위가 아니다</b> — LiveKit 의 삭제 API 는 ingressId 만 받고
     * 소속 검증을 하지 않으므로, 실제로는 전역 단건 삭제다. 시그니처가 "이 방의 ingress"처럼 읽히는 게
     * 위험한 지점: <b>ingressId 는 우리가 방금 만든 값이거나 LiveKit 목록에서 온 값이어야 한다.</b>
     * DB 컬럼이나 요청 파라미터에서 온 id 를 그대로 넘기면 남의 방 송출을 끊을 수 있다
     * (그 경우 여기서 검증할 게 아니라, 호출 전에 소속을 확인해야 한다).
     */
    void deleteIngress(UUID roomId, String ingressId);
    void closeRoom(String sfuRoomId);
    String getSfuUrl();
    List<IngressSummary> listAllIngress();
    List<EgressSummary> listAllEgress();
}
