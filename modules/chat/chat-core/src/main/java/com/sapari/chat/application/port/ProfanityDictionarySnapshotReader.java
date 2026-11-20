package com.sapari.chat.application.port;

import com.sapari.chat.port.ReactiveChat;

import reactor.core.publisher.Mono;

/**
 * 전파된 사전 스냅샷을 읽는다 — <b>streaming-app 쪽 포트</b>다.
 *
 * <p>이 앱에는 관계형 DB 가 없어서 정본을 직접 읽을 수 없다. 읽는 것은 live-app 이 떠 놓은
 * 스냅샷뿐이고, 그래서 이 포트가 보는 값은 정본의 <b>복사본</b>이다 — 그 사실이 아래 두 메서드가
 * 따로 있는 이유다.
 */
@ReactiveChat
public interface ProfanityDictionarySnapshotReader {

    /**
     * 스냅샷 한 장. 아직 없으면 <b>빈 {@code Mono}</b> 다.
     *
     * <p>"없음" 과 "비었음" 을 가르는 것이 호출자에게 중요하다. 없음은 정본이 아직 한 번도
     * 발행되지 않은 상태라 번들 사전으로 떠야 하고, 비었음은 거부해야 한다(마스킹이 조용히
     * 꺼진다). 그래서 비어 있는 스냅샷은 {@code Mono.empty()} 가 아니라 항목 0개짜리 값으로
     * 내려오고, 거부는 사전 생성 쪽 문이 한다.
     */
    Mono<ProfanityDictionarySnapshot> read();

    /**
     * 지금 발행돼 있는 버전만 읽는다 — <b>주기 자가치유</b>가 쓴다.
     *
     * <p>Redis Pub/Sub 은 비영속이라 구독이 끊긴 사이에 나간 알림은 재연결해도 오지 않는다.
     * 그 알림을 놓친 Pod 은 알림에만 기대면 <b>재시작할 때까지</b> 옛 사전으로 집행하고, 증상이 "어떤 Pod 은
     * 막고 어떤 Pod 은 안 막는다" 라 원인이 사전 쪽으로 수렴하지 않는다. 그래서 알림에만
     * 기대지 않고 주기로 이 값을 보고, 로컬과 다를 때만 {@link #read()} 로 내려간다.
     *
     * <p>스냅샷 전체가 아니라 버전만 읽는 이유는 비용이다 — 스냅샷은 수십 KB 이고 이 값은
     * 몇 바이트다. 둘이 어긋날 수는 없다: 발행이 카운터 증가와 본문 쓰기를 한 스크립트에서
     * 하므로 밖에서 둘을 따로 볼 수 없다.
     *
     * <p>아직 발행된 적이 없으면 빈 {@code Mono} 다.
     */
    Mono<Long> readVersion();
}
