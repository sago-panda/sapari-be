package com.sapari.streamingapp.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.streamingapp.websocket.ChatSessionRegistry;
import com.sapari.streamingapp.websocket.ChatWebSocketHandler;
import com.sapari.streamingapp.websocket.EntryGate;
import com.sapari.streamingapp.websocket.auth.RoomTokenVerifier;

/**
 * 채팅 WS 라우팅 — {@code /ws/chat}을 {@link ChatWebSocketHandler}로 매핑한다.
 *
 * <p>핸들러는 @Component가 아니라 여기서 생성한다(SendChatUseCase 등 풀와이어가 갖춰지는 시점에 등록).
 * reactive WS는 URL→handler {@link SimpleUrlHandlerMapping} + {@link WebSocketHandlerAdapter}가 필요하다.
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public ChatWebSocketHandler chatWebSocketHandler(RoomTokenVerifier verifier, EntryGate entryGate,
            ChatSessionRegistry registry, SendChatUseCase sendUseCase, ChatBroadcastSubscriber subscriber) {
        return new ChatWebSocketHandler(verifier, entryGate, registry, sendUseCase, subscriber);
    }

    @Bean
    public HandlerMapping chatWebSocketMapping(ChatWebSocketHandler handler) {
        // 일반 라우트보다 먼저 매칭되도록 최우선 순위
        return new SimpleUrlHandlerMapping(Map.of("/ws/chat", handler), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
