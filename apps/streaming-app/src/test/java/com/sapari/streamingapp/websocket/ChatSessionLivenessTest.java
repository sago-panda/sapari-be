package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * 생존 확인이 <b>실제 소켓에서</b> 성립하는지 본다 — 이 기능 전체가 걸린 프레임워크 사실 하나를
 * 측정하는 자리다.
 *
 * <p>서버가 ping 을 보내고 그 pong 이 {@code session.receive()} 로 돌아와야 생존 시계가 밀린다.
 * 돌아오지 않으면 <b>멀쩡한 연결이 기한마다 전부 끊긴다</b> — 아무것도 보내지 않는 시청자가 채팅의
 * 정상 다수라서, 그 경우 서비스가 통째로 망가진다. 단위 테스트는 레지스트리가 "응답 있음"이라고
 * 답하도록 목으로 세우므로 이 사실을 재지 못한다.
 *
 * <p>상한을 짧게 주려고 별도 컨텍스트를 쓴다 — 같은 값을 기존 WS 통합 테스트에 걸면 그쪽 테스트들이
 * 진행 중에 끊긴다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "chat.session.ping-interval=1s",
                "chat.session.liveness-deadline=3s",
                "chat.session.max-lifetime=10m"
        })
@Testcontainers
@DisplayName("생존 확인 — 서버 ping / 클라 pong")
class ChatSessionLivenessTest {

    private static KeyPair liveKeys;

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Value("${local.server.port}")
    int port;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        liveKeys = gen.generateKeyPair();
    }

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("chat.room-token.public-key",
                () -> Base64.getEncoder().encodeToString(liveKeys.getPublic().getEncoded()));
    }

    private String roomToken(PrivateKey key, UUID roomId, UUID userId) {
        return Jwts.builder()
                .issuer("live")
                .audience().add("chat").and()
                .subject(userId.toString())
                .claim("room", roomId.toString())
                .claim("role", "BUYER")
                .claim("owner", false)
                .claim("nickname", "구매자")
                .claim("email", "b@example.com")
                .expiration(new Date(System.currentTimeMillis() + 90_000))
                .signWith(key)
                .compact();
    }

    private HttpHeaders tokenHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Sec-WebSocket-Protocol", "bearer," + token);
        return headers;
    }

    @Test
    @DisplayName("⭐ 말하지 않는 클라도 기한을 넘겨 살아 있다 — 서버 ping 의 pong 이 인바운드로 돌아온다")
    void silent_client_survives_past_the_liveness_deadline() {
        // given: 입장 후 아무것도 보내지 않는 클라. 채팅의 정상 다수다.
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId);

        List<String> frames = new CopyOnWriteArrayList<>();

        // when: 기한(3초)을 한참 넘긴 5초까지 아무것도 보내지 않다가, 그 뒤에 한 건 보낸다.
        //
        // 단언을 "닫히지 않았다"로 쓸 수 없다 — 수신을 끝내는 순간 클라가 스스로 닫으므로 그 close 와
        // 서버가 끊은 close 가 구별되지 않는다(둘 다 1000이다). 기한 뒤의 <b>왕복</b>이 그 둘을 가른다:
        // 서버가 3초에 끊었다면 ACK 가 올 곳이 없다.
        new ReactorNettyWebSocketClient().execute(URI.create(
                "ws://localhost:" + port + "/ws/chat?roomId=" + roomId), tokenHeaders(token), session ->
                session.send(Mono.just(session.textMessage(
                                "{\"type\":\"NORMAL\",\"content\":\"살아있나\",\"clientMsgId\":\"live-1\"}"))
                                .delaySubscription(Duration.ofSeconds(5)))
                        .and(session.receive().map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frames::add).take(Duration.ofSeconds(9)).then()))
                .block(Duration.ofSeconds(40));

        // then: 입장 안내를 받았고, 기한을 세 배 넘긴 뒤 보낸 메시지가 ACK 로 돌아왔다.
        //
        // ACK 가 없으면 서버가 생존 확인으로 끊은 것이다 — 즉 ping 에 대한 pong 이 인바운드로
        // 돌아오지 않는다는 뜻이고, 그러면 이 기능은 "소켓이 살아 있는가" 대신 "클라가 말을 하는가"를
        // 재게 되어 보는 것만 하는 시청자를 기한마다 전부 끊는다.
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"ROOM_INFO\""));
        assertThat(frames)
                .as("생존 기한을 넘긴 뒤 왕복이 안 된다 — 서버가 말하지 않는 클라를 끊었다")
                .anyMatch(f -> f.contains("\"type\":\"ACK\"") && f.contains("\"clientMsgId\":\"live-1\""));
    }
}
