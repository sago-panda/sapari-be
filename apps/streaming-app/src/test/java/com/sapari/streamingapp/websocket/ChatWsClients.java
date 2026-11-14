package com.sapari.streamingapp.websocket;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import reactor.core.publisher.Mono;

/**
 * 테스트용 WS 접속 — 서브프로토콜을 브라우저처럼 <b>선언</b>해서 보낸다.
 *
 * <p>원시 {@code Sec-WebSocket-Protocol} 헤더로만 실으면 Netty 클라이언트는 응답의 프로토콜을 검사하지 않는다.
 * 그 구성에서는 서버가 {@code bearer} 를 돌려주지 않아도 통과하고, 돌려주면 오히려
 * {@code Invalid subprotocol. Actual: bearer. Expected one of: null} 로 실패한다. 핸들러의
 * {@code getSubProtocols()} 로 선언하면 요청에 실리고, 응답이 그중 하나가 아니면 핸드셰이크가 실패한다.
 */
final class ChatWsClients {

    private ChatWsClients() {
    }

    /** 룸 토큰을 {@code ["bearer", token]} 으로 선언해 접속한다 — 프론트 계약과 같은 모양. */
    static Mono<Void> connect(URI uri, String token, Function<WebSocketSession, Mono<Void>> body) {
        return connect(uri, List.of("bearer", token), body);
    }

    static Mono<Void> connect(URI uri, List<String> subProtocols, Function<WebSocketSession, Mono<Void>> body) {
        return new ReactorNettyWebSocketClient().execute(uri, new WebSocketHandler() {
            @Override
            public List<String> getSubProtocols() {
                return subProtocols;
            }

            @Override
            public Mono<Void> handle(WebSocketSession session) {
                return body.apply(session);
            }
        });
    }
}
