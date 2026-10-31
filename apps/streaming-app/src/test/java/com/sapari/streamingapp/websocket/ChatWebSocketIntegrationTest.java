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


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
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
        redisTemplate.opsForSet().add("chat:kicked:" + roomId, userId.toString()).block(VERIFY_CEILING);
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

    @Test
    @DisplayName("⭐ 서버발 종료는 유예를 기다리지 않는다 — 데이터 채널이 닫히면 그 자리에서 끊긴다")
    void server_initiated_close_does_not_wait_for_the_forced_grace() {
        // given: 접속 중 강퇴. 종료 경로가 둘이다 — 데이터 채널(sink complete → outbound 완료)이
        // 정상 경로이고, 제어 채널 + 유예(3초)는 그게 막혔을 때의 탈출로다.
        //
        // 아웃바운드에 끝나지 않는 publisher 가 섞이면 session.send 가 영원히 완료되지 않아
        // <b>정상 경로가 도달 불가</b>가 된다. 그러면 강퇴·밴·방 종료·버퍼 초과·수명·생존이 전부
        // 유예 경로로만 닫히고, 그 사이 세션 명부·방 인덱스·방 구독·Redis 항목이 함께 남는다
        // (방 종료 때는 그 방 전체가 동시에).
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        String kickEnvelope = "{\"kind\":\"KICK_EVENT\",\"userId\":\"" + userId + "\"}";

        AtomicReference<Long> kickedAtNanos = new AtomicReference<>();
        AtomicReference<Long> closedAtNanos = new AtomicReference<>();
        List<String> received = new CopyOnWriteArrayList<>();

        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session -> {
            session.closeStatus().doOnNext(ignored -> closedAtNanos.set(System.nanoTime())).subscribe();
            Mono<Void> receive = session.receive().map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(received::add).then();
            Mono<Void> kick = Mono.delay(Duration.ofMillis(700))
                    .doOnNext(ignored -> kickedAtNanos.set(System.nanoTime()))
                    .then(redisTemplate.convertAndSend("chat:pubsub:" + roomId, kickEnvelope))
                    .then();
            return receive.and(kick);
        }).block(Duration.ofSeconds(20));

        // then: 유예(3초)를 기다렸다면 정상 경로가 죽어 있다는 뜻이다.
        // 1초는 그 둘을 가르는 값이다 — 정상 경로는 수십 ms 이고 유예 경로는 3초다.
        assertThat(kickedAtNanos.get()).as("강퇴 발행 시각이 안 잡혔다").isNotNull();
        assertThat(closedAtNanos.get()).as("세션이 닫히지 않았다").isNotNull();

        // ⚠️ <b>강퇴 팬아웃을 실제로 탔는지를 먼저 본다.</b> 아래 경과시간만 보면 이 테스트에
        // 초록인 채 아무것도 재지 않는 모드가 있다 — kickedAtNanos 는 convertAndSend 앞에서 무조건
        // 찍히고 closedAtNanos 는 <b>어떤 이유의</b> close 에도 찍히므로, 세션이 강퇴보다 먼저 닫히면
        // 경과시간이 음수가 되어 "1초 미만" 을 만족한다. 리뷰어가 그 모드를 재현했다 — 토큰을 위조해
        // 입장 거부로 ~50ms 에 끊으니 이 테스트가 팬아웃을 한 번도 타지 않은 채 통과했고 형제
        // 테스트들은 정상적으로 실패했다. SYSTEM(KICKED) 수신이 "팬아웃이 돌았다" 의 양성 증거다.
        assertThat(received)
                .as("SYSTEM(KICKED)이 오지 않았다 — 강퇴 팬아웃을 타지 않았으므로 아래 경과시간은 "
                        + "강퇴와 무관한 종료를 재고 있다")
                .anySatisfy(payload -> assertThat(payload).contains("KICKED"));

        long elapsedMillis = (closedAtNanos.get() - kickedAtNanos.get()) / 1_000_000;
        assertThat(elapsedMillis)
                .as("서버발 종료가 유예를 기다렸다 — 데이터 채널 종료 경로가 도달 불가다")
                .isBetween(0L, 999L);
    }

    @Test
    @DisplayName("⭐ 계약상 최대 메시지는 프레임 상한에 걸리지 않는다 — 상한을 낮추면 긴 글만 조용히 사라진다")
    void largest_legitimate_message_still_passes() {
        // given: 본문 상한을 꽉 채운 한글 200자 + clientMsgId 상한 64자. 한글은 UTF-8에서 글자당 3바이트라
        // 영문 기준으로 상한을 잡으면 여기서 처음 걸린다.
        //
        // 이 테스트가 프레임 상한의 <b>아래쪽</b>을 지킨다. 위쪽만 지키면 상한을 계속 낮추는 변경이
        // 통과하고, 그러면 정당한 긴 메시지가 사유 프레임도 없이 사라진다 — 클라에게는 "긴 글만
        // 안 간다"로 보이고 서버 로그에는 아무것도 남지 않는다.
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        // 와이어 바이트를 <b>이스케이프 최악</b>으로 만든다. 생 UTF-8 한글은 글자당 3바이트(~714B)인데
        // 클라가 JSON 유니코드 이스케이프로 보내면 글자당 6바이트다 — 상한을 유도할 때 쓴 바로 그
        // 최악이고, 생 바이트만 보내면 상한을 1KB 로 낮추는 변경이 통과한다(리뷰어 실측). 그러면
        // 계약상 정당한 긴 메시지가 사유 프레임도 없이 연결째 끊긴다.
        //
        // 접두를 런타임에 조립하는 이유: 자바는 <b>주석과 문자열 안에서도</b> 유니코드 이스케이프를
        // 토큰화 전에 먼저 해석한다(JLS 3.3). 그 표기를 소스에 그대로 쓰면 컴파일이 깨진다.
        String escape = "\\" + "u";
        String longestContent = (escape + "AC00").repeat(200);     // '가' 200자를 이스케이프 표기로
        String longestClientMsgId = (escape + "0063").repeat(64);  // 'c' 64자를 이스케이프 표기로
        String payload = "{\"type\":\"NORMAL\",\"content\":\"" + longestContent
                + "\",\"clientMsgId\":\"" + longestClientMsgId + "\"}";

        // when
        List<String> frames = collect(roomId, token, payload, Duration.ofSeconds(4));

        // then: 저장됐고 ACK가 그 상관관계 id를 그대로 돌려준다
        assertThat(frames)
                .as("계약상 최대 메시지가 프레임 상한에 걸렸다 — 상한이 본문·id 상한과 어긋났다")
                .anyMatch(f -> f.contains("\"type\":\"ACK\"") && f.contains("\"clientMsgId\":\"" + "c".repeat(64) + "\""));
    }

    @Test
    @DisplayName("⭐ 상한을 넘는 프레임은 프로토콜 계층에서 끊긴다 — 본문 검증까지 가지 않는다")
    void oversized_frame_is_rejected_before_parsing() {
        // given: 본문 상한(200자)을 한참 넘지만 Netty 기본 프레임 상한(64KB)에는 못 미치는 프레임.
        //
        // 이 구간이 비어 있으면 공격이 싸다 — 프레임이 끝까지 버퍼링되고 JSON 파싱까지 간 뒤에야
        // 본문 길이 검사가 거부한다. 거부 응답은 세션별로 스로틀되지만 그 앞의 수신·파싱 비용은
        // 매 프레임 그대로 든다. 프로토콜 상한은 그 비용을 디코더에서 끊는다.
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = roomToken(liveKeys.getPrivate(), roomId, userId, "BUYER", false, "구매자", "b@example.com");
        String oversized = "{\"type\":\"NORMAL\",\"content\":\"" + "x".repeat(16_000)
                + "\",\"clientMsgId\":\"c1\"}";

        List<String> frames = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> observed = new AtomicReference<>();

        new ReactorNettyWebSocketClient().execute(wsUri(roomId), tokenHeaders(token), session -> {
            session.closeStatus().doOnNext(observed::set).subscribe();
            Mono<Void> receive = session.receive().map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(frames::add).take(Duration.ofSeconds(5)).then();
            return session.send(Mono.just(session.textMessage(oversized))
                    .delaySubscription(Duration.ofMillis(500))).and(receive);
        }).block(Duration.ofSeconds(25));

        // when & then: 입장 안내는 받았지만, 그 프레임에 대한 <b>본문 검증 응답은 오지 않는다</b> —
        // 디코더가 먼저 끊었다는 뜻이다. 상한이 없으면 파싱까지 가서 VALIDATION이 돌아온다.
        assertThat(frames).anyMatch(f -> f.contains("\"type\":\"ROOM_INFO\""));
        assertThat(frames)
                .as("상한을 넘는 프레임이 파싱까지 갔다 — 디코더가 끊지 않았다")
                .noneMatch(f -> f.contains("\"code\":\"VALIDATION\""));
        // 처분까지 고정한다 — "응답이 안 온다"만 보면 "조용히 버리고 연결은 유지"로 바뀌는 회귀가
        // 통과한다. 실제 처분은 1009 종료이고, 그 코드는 프론트 계약에 들어가야 하는 값이다.
        assertThat(observed.get()).as("상한 초과인데 세션이 닫히지 않았다").isNotNull();
        assertThat(observed.get().getCode())
                .as("상한 초과 처분이 1009(TOO_BIG_TO_PROCESS)가 아니다 — 프론트가 사유를 가릴 수 없다")
                .isEqualTo(1009);
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
