package com.sapari.streamingapp.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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
import com.sapari.streamingapp.websocket.SessionTimeouts;
import com.sapari.streamingapp.websocket.auth.RoomTokenVerifier;

/**
 * 채팅 WS 라우팅 — {@code /ws/chat}을 {@link ChatWebSocketHandler}로 매핑한다.
 *
 * <p>핸들러는 @Component가 아니라 여기서 생성한다(SendChatUseCase 등 풀와이어가 갖춰지는 시점에 등록).
 * reactive WS는 URL→handler {@link SimpleUrlHandlerMapping} + {@link WebSocketHandlerAdapter}가 필요하다.
 */
@Configuration
public class WebSocketConfig {

    /**
     * 연결 상한 셋. <b>빈으로 두는 이유는 수명을 두 곳이 쓰기 때문</b>이다 — 핸들러가 세션을 끊는 데
     * 쓰고, {@link RoomTokenVerifier} 가 "이 토큰이 그 수명보다 오래됐는가"를 묻는 데 쓴다. 각자
     * {@code @Value} 로 받으면 기본값이 두 벌이 되고, 그게 이 브랜치에서 반복해 잡은 갈림의 모양이다.
     *
     * <p>기본값 셋의 근거. 생존 기한 90s = 3 × ping 간격이다 — ping <b>두 번</b> 유실까지 견딘다는
     * 뜻이고, 그게 {@code SessionTimeouts} 의 "기한 >= 2 × 간격" 불변식과 같은 축이다(불변식은
     * 하한이고 이 값은 거기에 한 칸 더 준 것이다. 그 3배는 {@code ConfigTemplateTest} 가 따로 잰다 —
     * 불변식만으로는 2배까지 통과한다).
     *
     * <p>ping 30s 와 수명 60m 은 고른 값이다. 전자는 흔한 유휴 커넥션 타임아웃(LB·프록시가 보통 60s
     * 안팎)보다 짧게 둔 것이고, 후자는 "접속 후 바뀐 권위가 반영되기까지의 상한"이라 짧으면 재접속
     * 폭주가 늘고 길면 옛 권위가 오래 남는다. 측정된 값이 아니다.
     */
    @Bean
    public SessionTimeouts sessionTimeouts(
            @Value("${chat.session.max-lifetime:60m}") Duration maxLifetime,
            @Value("${chat.session.ping-interval:30s}") Duration pingInterval,
            @Value("${chat.session.liveness-deadline:90s}") Duration livenessDeadline) {
        return new SessionTimeouts(maxLifetime, pingInterval, livenessDeadline);
    }

    @Bean
    public ChatWebSocketHandler chatWebSocketHandler(RoomTokenVerifier verifier, EntryGate entryGate,
            ChatSessionRegistry registry, SendChatUseCase sendUseCase, ChatBroadcastSubscriber subscriber,
            NettyConnectionRegistry connections, SystemMessageService systemMessageService,
            SessionTimeouts timeouts) {
        return new ChatWebSocketHandler(verifier, entryGate, registry, sendUseCase, subscriber, connections,
                systemMessageService, timeouts);
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
