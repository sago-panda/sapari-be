package com.sapari.live.application.port;

import java.util.List;
import java.util.UUID;

public interface LiveMediaManager {
    SfuRoomResult createRoom(UUID roomId);
    String issueSellerToken(UUID roomId, UUID sellerId);
    IngressResult createIngress(UUID roomId, UUID sellerId);
    /**
     * 이 방에서 <b>실제 송출 중인 ingress 의 id</b>. 시작 시점 랑데부(판매자가 시작을 누른 순간 OBS 가 이미
     * 붙어 있는 경우) 판정에 쓴다. <b>조회 실패는 빈 목록</b>이다(fail-open).
     *
     * <p>{@link #listRoomIngress(UUID)} 와 조회 대상은 같고 <b>실패 방향만 반대</b>다 — 여기서 예외를 올리면
     * LiveKit 이 잠깐 흔들릴 때 판매자의 방송 시작 자체가 깨진다. 빈 목록이면 승격하지 않고 {@code Ready} 로
     * 저장되며, 곧 도착할 {@code ingress_started} webhook 이 전이를 이어받는다. 파괴적 판단(만료·삭제)의
     * 입력으로는 절대 쓰지 말 것 — 그쪽은 "모름"이 "지워라"로 읽힌다.
     *
     * <p><b>불리언이 아닌 이유</b>: 승격은 <b>방이 인정하는 ingress</b>가 송출 중일 때만 해야 한다. "누가
     * 송출 중인가"를 모르면 경합 패자 ingress 로도 승격되는데, 그 ingress 는 고아 미디어 잡이 회수하므로
     * <b>우리가 곧 끊을 방송을 시작시키는 꼴</b>이 된다. webhook·배치 경로와 같은 대조를 여기서도 하려면
     * id 가 필요하다.
     */
    List<String> publishingIngressIdsOrEmpty(UUID roomId);
    /**
     * 이 방 이름으로 등록된 ingress <b>전부</b>(각각 송출 중인지 포함). 조회 실패는 <b>예외</b>다.
     *
     * <p>{@link #publishingIngressIdsOrEmpty(UUID)} 와 실패 방향이 반대라 따로 둔다 — 시작 랑데부는 모르면 승격하지 않는 게
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
    /**
     * 이 방의 egress 전부. 종료 판정에 쓰므로 HTTP·전송 실패와 null body 는 예외다.
     * egress 가 정상적으로 없는 방은 빈 목록으로 반환한다.
     */
    List<EgressSummary> listRoomEgress(UUID roomId);
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
    /**
     * LiveKit 에 살아 있는 SFU 방 <b>전부</b>. 실패는 <b>예외</b>다({@link #listAllIngress()} 와 같은 이유 —
     * 빈 목록이 "정리할 방 없음"으로 읽혀 배치가 조용히 성공 종료한다).
     *
     * <p>이게 필요한 이유: 판매자 토큰은 TTL 이 6시간이고 <b>폐기 수단이 없다</b>. 종료 시 방을 지워도
     * 그 토큰으로 다시 join 하면 LiveKit 이 방을 되살린다. 그 방은 ingress 도 egress 도 만들지 않으므로
     * 위 두 목록에는 <b>잡히지 않는다</b> — 이 조회가 없으면 회수 주체가 아예 없다.
     */
    List<RoomSummary> listAllRooms();
}
