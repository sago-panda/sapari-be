package com.sapari.streamingapp.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

import reactor.netty.http.server.WebsocketServerSpec;

import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.streamingapp.websocket.ChatSessionRegistry;
import com.sapari.streamingapp.websocket.ChatWebSocketHandler;
import com.sapari.streamingapp.websocket.EntryGate;
import com.sapari.streamingapp.websocket.NettyConnectionRegistry;
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
            ChatSessionRegistry registry, SendChatUseCase sendUseCase, ChatBroadcastSubscriber subscriber,
            NettyConnectionRegistry connections, SystemMessageService systemMessageService) {
        return new ChatWebSocketHandler(verifier, entryGate, registry, sendUseCase, subscriber, connections,
                systemMessageService);
    }

    @Bean
    public HandlerMapping chatWebSocketMapping(ChatWebSocketHandler handler) {
        // 일반 라우트보다 먼저 매칭되도록 최우선 순위
        return new SimpleUrlHandlerMapping(Map.of("/ws/chat", handler), Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * 인바운드 프레임 크기 상한(바이트).
     *
     * <p><b>계약에서 유도한 값이다.</b> 인바운드는 {@code type}(NORMAL|NOTICE, 6자) ·
     * {@code content}(200자) · {@code clientMsgId}(64자) 셋뿐이고, 최악은 모든 글자가
     * {@code \\uXXXX}로 이스케이프돼 오는 경우다 — 본문 1,200B + 상관관계 id 384B + 키·구두점 ≈ 1.7KB.
     * 4KB는 그 2배가 넘고, 기본값(64KB)보다 16배 작다.
     *
     * <p>왜 필요한가: 상한이 없으면 본문 길이 검사가 <b>프레임을 끝까지 버퍼링하고 JSON을 파싱한
     * 뒤에야</b> 거부한다. 거부 <i>응답</i>은 세션별로 스로틀되지만 그 앞의 수신·파싱 비용은 매 프레임
     * 그대로 든다. 디코더에서 끊으면 그 비용이 사라진다.
     *
     * <p>⚠️ 본문 상한({@code SendChatService})이나 {@code clientMsgId} 상한
     * ({@code ChatConstants.MAX_CLIENT_MSG_ID_LENGTH})을 키우면 이 값도 함께 봐야 한다. 안 보면 늘린
     * 상한이 <b>디코더에서 먼저 끊겨</b> 정당한 메시지가 사유 없이 사라지고, 그 증상은 "긴 메시지만
     * 안 간다"로 나타난다.
     */
    private static final int MAX_FRAME_PAYLOAD_BYTES = 4 * 1024;

    /**
     * WS 어댑터 — 프레임 크기 상한을 걸기 위해 업그레이드 전략을 손수 세운다.
     *
     * <p>기본 생성자({@code new WebSocketHandlerAdapter()})는 전략도 기본값으로 만들어 상한이
     * Netty 기본(64KB)이 된다. 상한은 핸드셰이크 때 디코더에 박히므로 여기서만 정할 수 있다 —
     * 핸들러 쪽에서는 이미 디코딩된 메시지를 받으므로 손댈 자리가 없다.
     */
    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter(new HandshakeWebSocketService(
                new ReactorNettyRequestUpgradeStrategy(() -> WebsocketServerSpec.builder()
                        .maxFramePayloadLength(MAX_FRAME_PAYLOAD_BYTES))));
    }
}
