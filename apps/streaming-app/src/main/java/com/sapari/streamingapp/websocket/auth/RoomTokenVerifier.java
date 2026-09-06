package com.sapari.streamingapp.websocket.auth;

import java.security.PublicKey;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * 룸 토큰(live 발급) 검증 — WS 핸드셰이크의 유일한 인증 수단.
 *
 * <p><b>왜 이것만 보나</b>: live가 enter에서 access를 검증(폐기 포함)하고 라이브 여부·방주인까지 권위 평가해
 * 서명한 토큰이므로, chat은 access·blacklist를 다시 볼 필요가 없다(룸 토큰은 단명). 신원·방권위가 이 토큰
 * 한 장에 다 들어 있다.
 *
 * <p><b>왜 비대칭(RS256)인가</b>: 발급은 live(개인키)만, chat은 공개키로 <i>검증만</i> 한다. 대칭(HMAC) 공유키였다면
 * chat이 owner=true 토큰을 위조할 수 있으나(= PII 게이트 붕괴), 공개키로는 서명을 만들 수 없어 위조가 구조적으로 불가하다.
 * 공개키는 정적 설정으로 주입(키 회전이 필요해지면 헤더 kid + JWKS로 승급).
 */
@Component
public class RoomTokenVerifier {

    private static final String ISSUER = "live";
    private static final String AUDIENCE = "chat";

    /** 입장 가능한 role 화이트리스트 — SYSTEM(서버 내부 발신 주체)은 입장 세션에 와선 안 된다. */
    private static final Set<ChatRole> PARTICIPANT_ROLES =
            EnumSet.of(ChatRole.BUYER, ChatRole.SELLER, ChatRole.ADMIN, ChatRole.GUEST);

    private final JwtParser parser;

    public RoomTokenVerifier(PublicKey roomTokenPublicKey) {
        // verifyWith(공개키): RS256 서명 검증 / requireIssuer: iss!=live면 거부 / exp는 *있을 때만* parse가 만료 검사(부재는 parseToSession에서 강제)
        this.parser = Jwts.parser()
                .verifyWith(roomTokenPublicKey)
                .requireIssuer(ISSUER)
                .build();
    }

    /**
     * 룸 토큰을 검증해 {@link ChatSession}을 구성한다.
     *
     * @param token          쿼리 파라미터로 받은 룸 토큰(JWT)
     * @param expectedRoomId WS 경로가 접속하려는 방 — 토큰의 room 클레임과 일치해야 함(타 방 토큰 재사용 차단)
     * @return 검증 통과 시 ChatSession, 실패 시 {@link WebSocketAuthException} 에러 시그널
     */
    public Mono<ChatSession> verify(String token, UUID expectedRoomId) {
        return Mono.fromCallable(() -> parseToSession(token, expectedRoomId))
                // 직접 던진 WebSocketAuthException은 그대로, jjwt/파싱/불변식 예외는 인증 실패로 변환(상세는 와이어로 안 나감)
                .onErrorMap(e -> (e instanceof WebSocketAuthException) ? e
                        : new WebSocketAuthException("룸 토큰 검증 실패: " + e.getClass().getSimpleName()));
    }

    private ChatSession parseToSession(String token, UUID expectedRoomId) {
        Claims claims = parser.parseSignedClaims(token).getPayload();   // 서명·iss 검증 + exp가 있으면 만료 검증

        // exp 존재 강제 — jjwt는 exp가 *있을 때만* 만료를 검사한다. blacklist를 제거한 모델에서 짧은 TTL(exp)이
        // 유일한 폐기 백스톱이라, exp 부재 토큰은 영구 입장권이 되므로 거부한다.
        if (claims.getExpiration() == null) {
            throw new WebSocketAuthException("exp 누락");
        }

        // aud == chat (토큰 혼동/타 용도 토큰 재사용 차단) — jjwt에서 aud는 Set
        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(AUDIENCE)) {
            throw new WebSocketAuthException("aud != chat");
        }

        // room == 접속하려는 방
        UUID tokenRoom = UUID.fromString(claims.get("room", String.class));
        if (!tokenRoom.equals(expectedRoomId)) {
            throw new WebSocketAuthException("room 불일치");
        }

        UUID userId = UUID.fromString(claims.getSubject());
        ChatRole role = ChatRole.valueOf(claims.get("role", String.class));
        if (!PARTICIPANT_ROLES.contains(role)) {
            throw new WebSocketAuthException("입장 불가 role: " + role);   // SYSTEM 등 서버 내부 role 차단
        }
        boolean owner = Boolean.TRUE.equals(claims.get("owner", Boolean.class));
        String nickname = claims.get("nickname", String.class);   // 게스트는 null
        String email = claims.get("email", String.class);         // 게스트는 null

        // owner=true인데 role!=SELLER면 ChatSession 컴팩트 생성자가 IllegalArgumentException → 인증 실패로 변환(불변식 이중체크)
        return new ChatSession(expectedRoomId, userId, role, nickname, email, owner);
    }
}
