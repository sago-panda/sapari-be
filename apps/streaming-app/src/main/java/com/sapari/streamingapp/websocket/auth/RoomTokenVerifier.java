package com.sapari.streamingapp.websocket.auth;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.global.time.TimeProvider;
import com.sapari.streamingapp.websocket.SessionTimeouts;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * 룸 토큰(live 발급) 검증 — WS 핸드셰이크의 유일한 인증 수단.
 *
 * <p><b>왜 이것만 보나</b>: live가 enter에서 access를 검증하고 라이브 여부·방주인까지 권위 평가해 서명한
 * 토큰이므로, chat은 access·blacklist를 다시 볼 필요가 없다(룸 토큰은 단명). 신원·방권위가 이 토큰 한 장에
 * 다 들어 있다.
 *
 * <p>⚠️ 한때 그 검증이 <b>"폐기 포함"</b>이라 적혀 있었다. <b>아니다.</b> live-app 의 인증 필터는 서명·만료와
 * claims 만 보고 user DB·폐기 저장소에 의존하지 않으며, 그 파일이 스스로 "로그아웃/차단된 토큰은 access TTL
 * 만료까지 유효하다(폐기 갭)"고 적어 두었다. 역할·신원이 그 claims 에서 와 룸 토큰으로 들어오므로,
 * <b>역할 강등·탈퇴·로그아웃은 재접속으로도 반영되지 않는다</b> — access 가 만료될 때까지다.
 * 방 상태(종료·소유권)는 live DB 에서 오므로 재접속에서 제대로 재평가된다. 둘을 섞어 읽지 말 것.
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
    private final SessionTimeouts timeouts;
    private final TimeProvider timeProvider;

    public RoomTokenVerifier(PublicKey roomTokenPublicKey, SessionTimeouts timeouts,
            TimeProvider timeProvider) {
        this.timeouts = timeouts;
        this.timeProvider = timeProvider;
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

    /**
     * 토큰이 <b>세션 수명보다 오래됐으면</b> 입장을 거부한다.
     *
     * <p><b>무엇을 막는가.</b> role·방 소유가 이 토큰에 얼어 있고 입장 게이트는 종료·강퇴·밴만 다시
     * 본다. 그래서 세션이 수명으로 끊긴 뒤 <b>같은 토큰으로</b> 다시 들어오면 낡은 role·소유가
     * 그대로 되살아난다 — 룸 토큰은 {@code jti} 가 없어 {@code exp} 안에서 재사용 가능하다
     * (live 쪽 설정이 "창 자체가 방어" 라고 적는다). 그 재사용으로 낡은 권위가 쓰이는 마지막 시점은
     * <b>(토큰으로 세션을 시작할 수 있는 마지막 시점) + 수명</b>이고, 이 검사는 그 앞항을
     * {@code exp} 가 아니라 <b>수명</b>으로 묶는다.
     *
     * <p><b>얼마나 버는가 — 작다, 그리고 그게 정직한 값이다.</b> 상한이
     * {@code exp + 수명} → {@code min(exp, 수명) + 수명} 이 되므로 이득은 {@code max(0, exp - 수명)}
     * 이다. 즉 <b>수명 >= exp 인 설정에서는 0</b>이고 기본값(수명 60m · exp 90s)과 설정 템플릿이 적은
     * 탈출로(2m)가 전부 그쪽이다. 이 검사가 사는 자리는 수명을 {@code exp} 아래로 내린 구성 하나이고,
     * 거기서 "그 아래로 내리면 더 이상 상한이 아니다" 라는 템플릿 경고가 <b>집행</b>으로 바뀐다.
     * (근본 수정은 live 가 {@code jti} 를 넣고 소비 측이 한 번만 받는 것이다 — 이 저장소 밖이다.)
     *
     * <p><b>{@code iat} 부재는 거부다.</b> {@code exp} 부재를 거부하는 것과 같은 이유다 — 나이를 모르면
     * 이 상한이 집행되지 않는데, 조용히 집행을 잃는 쪽이 시끄럽게 막히는 쪽보다 나쁘다. live 가 지금
     * {@code issuedAt} 을 넣고 있고({@code RoomTokenProvider.issue}) 그 사실이 와이어 계약으로
     * {@code modules/chat/AGENTS.md} 에 적혀 있다. 그쪽이 빠지면 입장이 전면 실패하므로 바로 드러난다.
     *
     * <p>시계가 앞서면({@code iat} 이 미래) 나이가 음수라 통과한다 — 거부 방향으로 기울이지 않는다.
     * 큰 시계 차이는 어차피 {@code exp} 검증이 먼저 막는다.
     */
    private void rejectIfOlderThanSessionLifetime(Date issuedAt) {
        if (issuedAt == null) {
            throw new WebSocketAuthException("iat 누락");
        }
        Duration age = Duration.between(issuedAt.toInstant(), timeProvider.now());
        if (age.compareTo(timeouts.maxLifetime()) > 0) {
            throw new WebSocketAuthException("토큰이 세션 수명보다 오래됐다");
        }
    }

    private ChatSession parseToSession(String token, UUID expectedRoomId) {
        Claims claims = parser.parseSignedClaims(token).getPayload();   // 서명·iss 검증 + exp가 있으면 만료 검증

        // exp 존재 강제 — jjwt는 exp가 *있을 때만* 만료를 검사한다. blacklist를 제거한 모델에서 짧은 TTL(exp)이
        // 유일한 폐기 백스톱이라, exp 부재 토큰은 영구 입장권이 되므로 거부한다.
        if (claims.getExpiration() == null) {
            throw new WebSocketAuthException("exp 누락");
        }

        rejectIfOlderThanSessionLifetime(claims.getIssuedAt());


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
