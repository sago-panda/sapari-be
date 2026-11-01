package com.sapari.chat.application.port;

import java.util.UUID;
import java.util.function.Function;

import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;

import reactor.core.publisher.Mono;

/**
 * 세션 관리 추상화 — 각 Pod 로컬 메모리 레지스트리 + Redis HASH를 함께 조율한다.
 * 구현체 ChatSessionRegistry는 실제 WS 채널을 쥐고 있어 transport(streaming-app)에 둔다.
 */
public interface ChatSessionManager {

    /**
     * 세션을 등재한다. <b>거부할 수 있다.</b>
     *
     * <p>성공만 하는 호출이 아니다 — 같은 (유저, 방)의 동시 세션이 구현체의 상한을 넘으면 거부로
     * 끝난다(구현체는 전송 계층의 입장 거부 예외를 낸다). 거부는 그 사람의 자리가 비면 스스로
     * 풀리는 <b>일시 상태</b>이고, 강퇴·밴 같은 영구 상태가 아니다.
     *
     * <p>이 계약이 여기 적혀 있어야 하는 이유는 거부가 <b>부작용 없이</b> 끝난다는 것까지가 계약이기
     * 때문이다 — 거부된 호출은 로컬 색인·Redis 명부에 아무것도 남기지 않으므로, 호출자가 거부를
     * 받고 정리 작업을 따로 할 필요가 없다.
     *
     * <p>반대쪽도 계약이다 — <b>허용되면 호출자가 반드시 {@link #unregister} 로 짝을 맞춰야 한다.</b>
     * 이 호출은 자리를 점유하고 그 해제는 호출자의 종료 처리에 달려 있다. 짝을 빠뜨리면 그 자리가
     * 남아 같은 사람이 같은 방에 다시 들어올 수 없게 된다 — 조용히 틀리는 쪽이라 계약으로 적는다.
     */
    Mono<Void> register(String sessionId, ChatSession session);

    Mono<Void> unregister(UUID roomId, String sessionId);

    Mono<Void> closeUser(UUID roomId, UUID userId);

    Mono<Void> closeAll(UUID roomId);

    /**
     * 이 사람의 세션을 <b>방을 가리지 않고</b> 이 Pod에서 전부 끊는다.
     *
     * <p>{@link #closeUser(UUID, UUID)}와 갈라 두는 이유는 조치의 범위가 다르기 때문이다. 강퇴는 그 방
     * 하나이지만 밴·탈퇴는 계정 전체라, 그 사람이 다른 방에 열어 둔 세션도 끊어야 한다.
     *
     * <p>방 색인을 쓸 수 없어 이 Pod의 세션을 훑는다. 그래도 되는 이유는 이 조치가 드물기 때문이다 —
     * 방 fan-out은 메시지마다 돌아 색인이 필요했지만, 밴은 시간당 몇 건이다.
     */
    Mono<Void> closeUserEverywhere(UUID userId);

    // 고유 유저 수(HVALS distinct) — 멀티탭은 1로 집계
    Mono<Long> getActiveCount(UUID roomId);

    // publish 실패 시 로컬 에코 폴백용 — 해당 세션에 직접 송신
    Mono<Void> sendToSession(String sessionId, OutboundMessage message);

    // 이 Pod의 해당 방 로컬 세션 전체에 송신 — SYSTEM 로컬 렌더(SystemMessageService.renderToRoom)용
    Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message);

    /**
     * 이 사람의 세션에 <b>방을 가리지 않고</b> 같은 메시지를 보낸다.
     *
     * <p>끊기 직전에 사유를 알리는 데 쓴다. 순서가 계약이다 — 먼저 끊으면 sink가 닫혀 뒤이은 사유 프레임이
     * 조용히 사라지고, 클라이언트는 닫힌 이유를 모른 채 재접속을 시도한다.
     */
    Mono<Void> sendToUserEverywhere(UUID userId, OutboundMessage message);

    /**
     * 세션별 차등 팬아웃 — {@code resolver} 가 세션마다 보낼 메시지를 고른다({@code null} 이면 skip).
     * CHAT 의 방주인 PII 게이팅, KICK 의 당사자/타인 분기가 이걸 쓴다
     * ({@code ChatBroadcastSubscriber}).
     *
     * <p>단일 메시지를 받는 {@code sendToRoomLocal} 과 <b>이름을 분리한 것은 의도다</b> — 오버로드로
     * 두면 {@code any()} 매처가 어느 쪽인지 가리지 못해 테스트가 엉뚱한 쪽을 스텁한다.
     *
     * <p>⚠️ <b>resolver 는 같은 뷰에 대해 같은 인스턴스를 돌려줘야 한다.</b> 구현(transport)이 서로
     * 다른 메시지마다 와이어 문자열을 <b>한 번만</b> 만드는데, 그 묶음을 <b>객체 동일성</b>으로
     * 가른다. 세션마다 {@code new} 로 만들면 내용이 같아도 다른 객체라 묶이지 않고, 직렬화가 다시
     * 세션 수에 비례한다 — 실측으로 12,000 세션 한 메시지에 9.23ms 차이다.
     *
     * <p>그래서 호출부는 뷰 조합을 <b>미리 만들어 두고 고르기만</b> 한다. 예:
     * {@code ChatBroadcastSubscriber.fanOutChat} 은 특권 × 발신자 축으로 4종을 먼저 만든다.
     */
    Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver);
}
