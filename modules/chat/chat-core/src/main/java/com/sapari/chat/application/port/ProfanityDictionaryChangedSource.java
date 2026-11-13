package com.sapari.chat.application.port;

import com.sapari.chat.port.ReactiveChat;

import reactor.core.publisher.Flux;

/**
 * 사전이 바뀌었다는 알림 — <b>빠른 길이고, 정본이 아니다.</b>
 *
 * <p>Redis Pub/Sub 은 비영속이다. 구독이 끊긴 사이에 나간 알림은 재연결해도 오지 않고, 그
 * 알림을 놓친 Pod 은 알림에만 기대면 <b>재시작할 때까지</b> 옛 사전으로 집행한다 — 증상이 "어떤 Pod 은 막고
 * 어떤 Pod 은 안 막는다" 라 원인이 사전 쪽으로 수렴하지 않는다.
 *
 * <p>그래서 받는 쪽은 이 스트림에만 기대지 않고 주기로 버전을 확인한다
 * ({@link ProfanityDictionarySnapshotReader#readVersion()}). 이 포트가 하는 일은 그 주기를
 * <b>기다리지 않게</b> 해 주는 것뿐이다. 이 스트림이 통째로 죽어도 전파는 주기 간격만큼
 * 늦어질 뿐 멈추지 않는다 — 그것이 설계의 요점이다.
 */
@ReactiveChat
public interface ProfanityDictionaryChangedSource {

    /**
     * 새로 발행된 버전. 전 방 공용 채널 하나라 시작 시 1회 구독해 공유 스트림으로 둔다.
     *
     * <p>받는 쪽은 이 수를 <b>교체 판단에 쓰지 않는다</b> — 로그에만 쓴다("알림 v7 · 로컬 v5").
     * 판단은 스냅샷에 박힌 버전으로 한다: 알림과 스냅샷 사이에 또 발행이 나면 알림의 수는
     * 이미 낡았고, 그때 적용되는 것은 읽어 온 스냅샷이지 알림이 가리킨 것이 아니다.
     */
    Flux<Long> changed();
}
