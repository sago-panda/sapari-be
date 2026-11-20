package com.sapari.streamingapp.websocket.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.global.time.TimeProvider;
import com.sapari.streamingapp.websocket.SessionTimeouts;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import reactor.test.StepVerifier;

class RoomTokenVerifierTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    private static KeyPair liveKeys;     // live 발급용(개인키 서명)
    private static KeyPair otherKeys;    // 위조 서명 테스트용 (다른 키쌍)
    private static RoomTokenVerifier verifier;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void genKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        liveKeys = gen.generateKeyPair();
        otherKeys = gen.generateKeyPair();
        // chat은 live 공개키만 보유. 상한은 토큰 나이 검사에 쓰이고, 시계는 시스템 시계다 —
        // 아래 나이 케이스들만 시계를 옮긴 별도 검증기를 쓴다(잠들지 않고 경계를 재기 위해).
        verifier = verifierAt(Clock.systemUTC());
    }

    /** 토큰 나이 검사에 쓰이는 상한. 가드를 통과하는 조합이면서 60분보다 짧아 경계를 재기 쉽다. */
    private static final SessionTimeouts TIMEOUTS = new SessionTimeouts(
            Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(60));

    /**
     * 주어진 시계로 보는 검증기.
     *
     * <p>토큰의 {@code exp} 검증은 jjwt 자신의 시계(시스템)를 쓰고 나이 검사만 이 시계를 본다 —
     * 둘이 독립이라 <b>시계를 옮겨도 exp 가 멀쩡하다.</b> 그래서 2분을 기다리지 않고 경계를 잰다.
     */
    private static RoomTokenVerifier verifierAt(Clock clock) {
        return new RoomTokenVerifier(liveKeys.getPublic(), TIMEOUTS, new TimeProvider(clock));
    }

    /**
     * {@code iat} 를 명시해 서명한다 — 나이 경계를 정확히 재기 위해.
     *
     * <p>{@code exp} 는 실시간으로 둔다. jjwt 의 만료 검증은 자기 시계(시스템)를 보고 나이 검사만
     * 주입된 시계를 보므로, 둘을 섞지 않아야 "시계를 옮겨도 exp 는 멀쩡하다" 가 성립한다.
     */
    private String signIssuedAt(Instant issuedAt, Date exp) {
        return Jwts.builder()
                .issuer("live").audience().add("chat").and()
                .subject(userId.toString())
                .claim("room", roomId.toString())
                .claim("role", "BUYER")
                .claim("owner", false)
                .issuedAt(Date.from(issuedAt))
                .expiration(exp)
                .signWith(liveKeys.getPrivate())
                .compact();
    }

    private Date in60s() {
        return new Date(System.currentTimeMillis() + 60_000);
    }

    /** 룸 토큰 서명 헬퍼 — 모든 클레임을 인자로 받아 케이스별로 비튼다. */
    private String sign(PrivateKey key, String iss, String aud, UUID room, UUID sub,
            String role, boolean owner, String nickname, String email, Date exp) {
        return Jwts.builder()
                .issuer(iss)
                .audience().add(aud).and()
                .subject(sub.toString())
                .claim("room", room.toString())
                .claim("role", role)
                .claim("owner", owner)
                .claim("nickname", nickname)
                .claim("email", email)
                // live 의 발급기가 issuedAt 을 넣는다(RoomTokenProvider.issue) — 픽스처도 넣어야
                // 실제 토큰과 같은 모양이 된다. 나이 검사가 iat 부재를 거부하므로 빠지면 전부 깨진다.
                .issuedAt(new Date())
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /**
     * ⭐ 토큰이 세션 수명보다 오래되면 입장을 거부한다.
     *
     * <p>role·방 소유가 토큰에 얼어 있고 입장 게이트는 종료·강퇴·밴만 다시 본다. 룸 토큰은
     * {@code jti} 가 없어 {@code exp} 안에서 재사용 가능하므로, 세션이 수명으로 끊긴 뒤 같은 토큰으로
     * 다시 들어오면 낡은 권위가 되살아난다. 이 검사가 그 시작 창을 {@code exp} 가 아니라 수명으로 묶는다.
     *
     * <p>경계를 양쪽에서 잰다 — 정확히 수명이면 통과(엄격 비교), 1초 더 오래면 거부.
     */
    @Test
    @DisplayName("⭐ 토큰이 세션 수명보다 오래되면 거부 — 낡은 role·소유로 재입장하는 창을 수명으로 묶는다")
    void token_older_than_the_session_lifetime_is_rejected() {
        // given: iat 를 명시해 서명한다 — sign() 의 new Date() 는 ms 절삭이라 경계를 정확히 못 잰다
        Instant issued = Instant.parse("2026-06-18T00:00:00Z");
        String token = signIssuedAt(issued, in60s());

        // when & then: 수명(2분)을 1초 넘긴 시계로 보면 거부
        StepVerifier.create(verifierAt(Clock.fixed(issued.plus(TIMEOUTS.maxLifetime()).plusSeconds(1),
                        ZoneOffset.UTC)).verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);

        // 정확히 수명이면 통과한다 — "오래됐다"가 엄격 비교다
        StepVerifier.create(verifierAt(Clock.fixed(issued.plus(TIMEOUTS.maxLifetime()), ZoneOffset.UTC))
                        .verify(token, roomId))
                .assertNext(session -> assertThat(session.userId()).isEqualTo(userId))
                .expectComplete()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ iat 누락은 거부 — 나이를 모르면 그 상한이 집행되지 않는다")
    void missing_issued_at_is_rejected() {
        // given: live 가 넣는 issuedAt 이 빠진 토큰
        String token = Jwts.builder()
                .issuer("live").audience().add("chat").and()
                .subject(userId.toString())
                .claim("room", roomId.toString())
                .claim("role", "BUYER")
                .claim("owner", false)
                .expiration(in60s())
                .signWith(liveKeys.getPrivate())
                .compact();

        // when & then: 조용히 집행을 잃는 쪽보다 시끄럽게 막히는 쪽이다(exp 누락과 같은 결정)
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("iat 이 미래여도 통과 — 시계 차이를 거부 방향으로 기울이지 않는다")
    void a_future_issued_at_does_not_reject() {
        // given: 시계가 뒤처진 chat 이 보는 모양 — 나이가 음수다
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "BUYER", false, "구매자", "b@example.com", in60s());

        // when & then: 큰 시계 차이는 어차피 exp 검증이 먼저 막는다
        StepVerifier.create(verifierAt(Clock.fixed(Instant.now().minusSeconds(30), ZoneOffset.UTC))
                        .verify(token, roomId))
                .assertNext(session -> assertThat(session.userId()).isEqualTo(userId))
                .expectComplete()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("정상 — 회원 BUYER: ChatSession 구성, isRoomOwner=false")
    void valid_member_buyer() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "BUYER", false, "구매자닉", "buyer@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectNextMatches(s -> s.roomId().equals(roomId)
                        && s.userId().equals(userId)
                        && s.role() == ChatRole.BUYER
                        && !s.isRoomOwner()
                        && "구매자닉".equals(s.nickname())
                        && "buyer@example.com".equals(s.email()))
                .expectComplete().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("정상 — SELLER owner=true: isRoomOwner=true")
    void valid_seller_owner() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "SELLER", true, "판매자닉", "seller@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectNextMatches(s -> s.role() == ChatRole.SELLER && s.isRoomOwner())
                .expectComplete().verify(VERIFY_CEILING);
    }

    /**
     * ADMIN 룸 토큰이 세션이 되는 구간은 <b>SPR-143이 들어오기 전까지 한 번도 실행되지 않았다</b> —
     * live가 입장에서 ADMIN을 매핑하지 않아 그런 토큰이 발급될 수 없었다. 이 검증기는 그 role을 처음부터
     * 허용 목록에 갖고 있었지만 도달 불가였고, 그래서 이 앱 테스트에 {@code ADMIN}이 한 글자도 없었다.
     *
     * <p>지금은 관리자가 실제로 들어오고, 그 세션이 <b>원문·이메일을 받고 레이트리밋을 면제받으며 공지를
     * 쓸 수 있다.</b> 그 출발점이 여기라 고정해 둔다.
     */
    @Test
    @DisplayName("정상 — ADMIN owner=false: 관리자 세션이 만들어진다 (SPR-143으로 처음 도달)")
    void valid_admin() {
        // given: 관리자는 방을 소유하지 않는다 — owner=true 조합은 컴팩트 생성자가 거부한다
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "ADMIN", false, "관리자닉", "admin@sapari.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectNextMatches(s -> s.role() == ChatRole.ADMIN && !s.isRoomOwner())
                .expectComplete().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — owner=true인데 role=ADMIN (소유자는 반드시 SELLER)")
    void reject_admin_owner() {
        // given: live의 owner 계산은 role과 무관해서(userId==sellerId) 이 조합이 발급될 수 있다.
        //        그때 입장은 200인데 WS만 실패하므로, 여기서 거부되는 것이 진단의 유일한 단서다.
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "ADMIN", true, "관리자닉", "admin@sapari.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId)).expectError().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("정상 — 게스트 GUEST: nickname/email null, owner=false")
    void valid_guest() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "GUEST", false, null, null, in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectNextMatches(s -> s.role() == ChatRole.GUEST
                        && !s.isRoomOwner()
                        && s.nickname() == null
                        && s.email() == null)
                .expectComplete().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — owner=true인데 role=BUYER (ChatSession 불변식 이중체크)")
    void reject_owner_but_not_seller() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "BUYER", true, "닉", "e@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — aud != chat")
    void reject_wrong_audience() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "other", roomId, userId,
                "BUYER", false, "닉", "e@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — iss != live")
    void reject_wrong_issuer() {
        // given
        String token = sign(liveKeys.getPrivate(), "evil", "chat", roomId, userId,
                "BUYER", false, "닉", "e@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — 만료된 토큰")
    void reject_expired() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "BUYER", false, "닉", "e@example.com", new Date(System.currentTimeMillis() - 1_000));

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — room 클레임 != 접속하려는 방 (타 방 토큰 재사용)")
    void reject_room_mismatch() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", UUID.randomUUID(), userId,
                "BUYER", false, "닉", "e@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — 다른 키로 서명(위조) → live 공개키 검증 실패")
    void reject_bad_signature() {
        // given
        String token = sign(otherKeys.getPrivate(), "live", "chat", roomId, userId,
                "BUYER", false, "닉", "e@example.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — exp 클레임 부재(영구 토큰 방지)")
    void reject_missing_exp() {
        // given
        String token = Jwts.builder()
                .issuer("live").audience().add("chat").and()
                .subject(userId.toString())
                .claim("room", roomId.toString())
                .claim("role", "BUYER")
                .claim("owner", false)
                .signWith(liveKeys.getPrivate())
                .compact();   // exp 없음

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("거부 — role=SYSTEM(서버 내부 role) 입장 불가")
    void reject_system_role() {
        // given
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "SYSTEM", false, null, null, in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId))
                .expectError(WebSocketAuthException.class)
                .verify(VERIFY_CEILING);
    }
}
