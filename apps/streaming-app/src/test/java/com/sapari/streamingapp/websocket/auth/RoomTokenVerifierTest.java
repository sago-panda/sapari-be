package com.sapari.streamingapp.websocket.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.model.ChatRole;

import io.jsonwebtoken.Jwts;
import reactor.test.StepVerifier;

class RoomTokenVerifierTest {

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
        verifier = new RoomTokenVerifier(liveKeys.getPublic());   // chat은 live 공개키만 보유
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
                .expiration(exp)
                .signWith(key)
                .compact();
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
                .verifyComplete();
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
                .verifyComplete();
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
                .verifyComplete();
    }

    @Test
    @DisplayName("거부 — owner=true인데 role=ADMIN (소유자는 반드시 SELLER)")
    void reject_admin_owner() {
        // given: live의 owner 계산은 role과 무관해서(userId==sellerId) 이 조합이 발급될 수 있다.
        //        그때 입장은 200인데 WS만 실패하므로, 여기서 거부되는 것이 진단의 유일한 단서다.
        String token = sign(liveKeys.getPrivate(), "live", "chat", roomId, userId,
                "ADMIN", true, "관리자닉", "admin@sapari.com", in60s());

        // when & then
        StepVerifier.create(verifier.verify(token, roomId)).verifyError();
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
                .verifyComplete();
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
                .verify();
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
                .verify();
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
                .verify();
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
                .verify();
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
                .verify();
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
                .verify();
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
                .verify();
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
                .verify();
    }
}
