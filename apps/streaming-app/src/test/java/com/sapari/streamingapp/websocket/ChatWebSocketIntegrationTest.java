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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import reactor.core.publisher.Flux;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * C6 — WS 핸드셰이크~송수신 end-to-end. 풀 부팅(RANDOM_PORT) + Redis/Mongo 컨테이너 + 실제 RS256 키쌍.
 * live 개인키로 룸 토큰을 서명하고, 앱은 주입된 공개키로 검증 → 입장·ROOM_INFO·송신 ACK·위조 거부를 실증한다.
 *
 * <p>토큰은 쿼리가 아니라 Sec-WebSocket-Protocol 헤더(["bearer", token])로 전달한다(PII 토큰 access log 잔류 방지).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatWebSocketIntegrationTest {

    private static KeyPair liveKeys;    // 발급용(앱이 공개키로 검증)
    private static KeyPair otherKeys;   // 위조 서명용

    /**
     * 컨테이너 주소는 {@code @ServiceConnection}으로 붙인다 — 프로퍼티 이름을 테스트가 직접 적으면
     * 그 이름이 바뀌는 순간 조용히 기본값(로컬호스트)으로 돌아간다. 실제로 Boot 4에서
     * {@code spring.data.mongodb.*}가 {@code spring.mongodb.*}로 바뀌면서 이 테스트가 그 함정에 빠져 있었다:
     * 환경에는 컨테이너 주소가 들어 있는데 바인딩이 되지 않아 앱이 27017을 두드리다 타임아웃으로 죽었다.
     * 이름을 코드에서 들어내면 다음 개명에도 같은 일이 반복되지 않는다.
     */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Value("${local.server.port}")
    int port;

    @Autowired
    ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    NettyConnectionRegistry connections;

    @BeforeAll
    static void genKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        liveKeys = gen.generateKeyPair();
        otherKeys = gen.generateKeyPair();
    }

    /** 컨테이너 주소는 위 {@code @ServiceConnection}이 붙인다. 여기 남은 것은 앱 소유 설정뿐이다. */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("chat.room-token.public-key",
                () -> Base64.getEncoder().encodeToString(liveKeys.getPublic().getEncoded()));
    }

    private String roomToken(PrivateKey key, UUID roomId, UUID userId, String role, boolean owner,
            String nickname, String email) {
        return Jwts.builder()
                .issuer("live").audience().add("chat").and()
                .subject(userId.toString())
                .claim("room", roomId.toString())
                .claim("role", role)
                .claim("owner", owner)
                .claim("nickname", nickname)
                .claim("email", email)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }

    private URI wsUri(UUID roomId) {
        return URI.create("ws://localhost:" + port + "/ws/chat?roomId=" + roomId);
    }

    /** 룸 토큰을 Sec-WebSocket-Protocol 서브프로토콜(["bearer", token])로 실어 보낸다. */
    private HttpHeaders tokenHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Sec-WebSocket-Protocol", "bearer");
        headers.add("Sec-WebSocket-Protocol", token);
        return headers;
    }

    @Test
    @DisplayName("연결 — 유효 룸 토큰이면 ROOM_INFO를 받는다")
    void valid_token_receives_room_info() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");

        List<String> frames = new CopyOnWriteArrayList<>();
        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session ->
                session.receive().map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(frames::add).take(1).then())
                .block(Duration.ofSeconds(15));

        // when & then
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0)).contains("\"type\":\"ROOM_INFO\"");
    }

    @Test
    @DisplayName("송신 — NORMAL 전송 시 clientMsgId가 실린 ACK를 받는다")
    void send_receives_ack() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        String payload = "{\"type\":\"NORMAL\",\"content\":\"hello\",\"clientMsgId\":\"c1\"}";

        List<String> frames = new CopyOnWriteArrayList<>();
        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session ->
                session.send(Mono.just(session.textMessage(payload)).delaySubscription(Duration.ofMillis(500)))
                        .and(session.receive().map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frames::add).take(Duration.ofSeconds(3)).then()))
                .block(Duration.ofSeconds(20));

        // when & then
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"ROOM_INFO\""));
        // ACK에 clientMsgId + createdAt이 ISO-8601 문자열(따옴표)로 — epoch 숫자가 아님(프론트 계약, F2 회귀)
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"ACK\"")
                && f.contains("\"clientMsgId\":\"c1\"") && f.contains("\"createdAt\":\""));
    }

    @Test
    @DisplayName("거부 — 위조 서명(다른 키) 토큰이면 ROOM_INFO 없이 닫힌다")
    void forged_token_rejected() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String forged = roomToken(otherKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");

        List<String> frames = new CopyOnWriteArrayList<>();
        try {
            new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(forged), session ->
                    session.receive().map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(frames::add).then())
                    .block(Duration.ofSeconds(10));
        } catch (Exception ignored) {
            // 서버 1008 close가 클라에 에러로 surface될 수 있음 — 핵심은 ROOM_INFO 미수신
        }

        // when & then
        assertThat(frames).noneMatch(f -> f.contains("ROOM_INFO"));
    }

    @Test
    @DisplayName("강퇴 — kicked SET에 있으면 SYSTEM(KICKED) 후 ROOM_INFO 없이 닫힌다")
    void kicked_member_rejected() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        redisTemplate.opsForSet().add("chat:kicked:" + roomId, userId.toString()).block();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");

        List<String> frames = collect(roomId, token, null, Duration.ofSeconds(3));

        // when & then
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"SYSTEM\"") && f.contains("KICKED"));
        assertThat(frames).noneMatch(f -> f.contains("ROOM_INFO"));
    }

    @Test
    @DisplayName("권한 — BUYER가 NOTICE 시도하면 ERROR(PERMISSION)")
    void buyer_notice_denied() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        String notice = "{\"type\":\"NOTICE\",\"content\":\"공지\",\"clientMsgId\":\"n1\"}";

        List<String> frames = collect(roomId, token, notice, Duration.ofSeconds(3));

        // when & then
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"ERROR\"")
                && f.contains("PERMISSION") && f.contains("\"clientMsgId\":\"n1\""));
    }

    @Test
    @DisplayName("레이트리밋 — BUYER 연속 전송 시 2번째는 RATE_LIMIT")
    void rapid_send_rate_limited() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        String m1 = "{\"type\":\"NORMAL\",\"content\":\"a\",\"clientMsgId\":\"r1\"}";
        String m2 = "{\"type\":\"NORMAL\",\"content\":\"b\",\"clientMsgId\":\"r2\"}";

        List<String> frames = new CopyOnWriteArrayList<>();
        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session ->
                session.send(Flux.concat(
                                Mono.just(session.textMessage(m1)).delaySubscription(Duration.ofMillis(500)),
                                Mono.just(session.textMessage(m2)).delaySubscription(Duration.ofMillis(150))))
                        .and(session.receive().map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frames::add).take(Duration.ofSeconds(3)).then()))
                .block(Duration.ofSeconds(20));

        // when & then
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"RATE_LIMIT\"") && f.contains("\"clientMsgId\":\"r2\""));
    }

    @Test
    @DisplayName("거부 — 토큰 서브프로토콜 누락(bearer만)이면 ROOM_INFO 없이 닫힌다")
    void missing_token_rejected() {
        // given
        UUID roomId = UUID.randomUUID();
        HttpHeaders onlyBearer = new HttpHeaders();
        onlyBearer.add("Sec-WebSocket-Protocol", "bearer");   // 토큰 값 없음 → extractToken null

        List<String> frames = new CopyOnWriteArrayList<>();
        try {
            new ReactorNettyWebSocketClient().execute(wsUri(roomId), onlyBearer, session ->
                    session.receive().map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(frames::add).then())
                    .block(Duration.ofSeconds(10));
        } catch (Exception ignored) {
            // 서버 1008 close가 에러로 surface될 수 있음
        }

        // when & then
        assertThat(frames).noneMatch(f -> f.contains("ROOM_INFO"));
    }

    @Test
    @DisplayName("강퇴 — 접속 중 KICK_EVENT를 받으면 SYSTEM(KICKED) 후 1008로 닫힌다")
    void kicked_mid_session_closes_with_policy_violation() {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        String kickEnvelope = "{\"kind\":\"KICK_EVENT\",\"userId\":\"" + userId + "\"}";

        List<String> frames = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> observed = new AtomicReference<>();

        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session -> {
            session.closeStatus().doOnNext(observed::set).subscribe();
            Mono<Void> receive = session.receive().map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(frames::add).then();
            // 접속이 자리잡은 뒤 다른 Pod가 강퇴를 발행한 것처럼 봉투를 밀어넣는다
            Mono<Void> kick = Mono.delay(Duration.ofMillis(700))
                    .then(redisTemplate.convertAndSend("chat:pubsub:" + roomId, kickEnvelope))
                    .then();
            return receive.and(kick);
        }).block(Duration.ofSeconds(20));

        // when & then
        assertThat(frames).anyMatch(f -> f.contains("\"code\":\"KICKED\""));
        // 프론트가 "재접속하지 말 것"으로 읽는 신호는 close code다 — 와이어에 실제로 실려야 한다
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    /** WS 접속(토큰=서브프로토콜) → (옵션) 1건 송신 → window 동안 수신 프레임 수집. */
    private List<String> collect(UUID roomId, String token, String sendPayload, Duration window) {
        List<String> frames = new CopyOnWriteArrayList<>();
        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session -> {
            Mono<Void> receive = session.receive().map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(frames::add).take(window).then();
            if (sendPayload == null) {
                return receive;
            }
            return session.send(Mono.just(session.textMessage(sendPayload)).delaySubscription(Duration.ofMillis(500)))
                    .and(receive);
        }).block(window.plusSeconds(15));
        return frames;
    }

    @Test
    @DisplayName("커넥션 추적 — 접속하면 등록되고, 끊기면 스스로 빠진다(강제 회수의 전제)")
    void connections_are_tracked_and_released() throws InterruptedException {
        // given
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        AtomicReference<Integer> whileConnected = new AtomicReference<>();
        // 카운터는 이 Pod 전역이고 서버는 클래스 안 다른 테스트와 공유한다 — 0이 아니라 기준선과 비교한다.
        // 앞선 테스트의 채널 해제가 조금 늦으면 0 단언은 이 테스트를 엉뚱하게 깨뜨린다.
        int baseline = connections.trackedCount();

        // when: 접속이 살아있는 동안의 추적 수를 본다
        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session ->
                session.receive().map(WebSocketMessage::getPayloadAsText).take(1)
                        .doOnNext(f -> whileConnected.set(connections.trackedCount())).then())
                .block(Duration.ofSeconds(15));

        // then: 접속 중엔 늘고, 끊긴 뒤에는 돌아온다(맵 자체가 누수원이 되면 안 된다)
        assertThat(whileConnected.get()).isGreaterThan(baseline);
        // 해제는 서버 이벤트루프에서 일어나므로 잠깐 기다린다
        // 폴링 간격을 둔다 — busy-spin은 CI 병렬 실행에서 코어 하나를 5초 태워 다른 테스트를 느리게 만든다
        long deadline = System.currentTimeMillis() + 5_000;
        while (connections.trackedCount() > baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(connections.trackedCount()).isLessThanOrEqualTo(baseline);
    }
}
