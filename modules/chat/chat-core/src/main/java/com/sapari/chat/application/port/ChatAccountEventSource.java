package com.sapari.chat.application.port;

import com.sapari.chat.application.protocol.ChatAccountEvent;

import reactor.core.publisher.Flux;

/**
 * 계정 조치 이벤트 스트림. <b>리액티브</b> — 세션을 들고 있는 앱(streaming-app)에서 소비된다.
 *
 * <p>Pod당 하나이고 <b>상시 가동</b>이다. 방 구독처럼 입장/퇴장에 붙였다 떼면 그 사이에 발행된 이벤트를
 * 놓치는데, 이 이벤트는 놓치면 다시 오지 않는다(Pub/Sub은 영속이 아니다). 끊기면 스스로 다시 붙는다.
 */
public interface ChatAccountEventSource {

    Flux<ChatAccountEvent> events();
}
