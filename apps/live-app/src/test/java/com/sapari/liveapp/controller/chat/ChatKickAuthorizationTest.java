package com.sapari.liveapp.controller.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sapari.chat.port.KickUserUseCase;
import com.sapari.chat.port.ClearRoomEndedMarkerUseCase;
import com.sapari.chat.port.ReconcileKickListUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.config.EnterRateLimitConfig;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.chat.domain.exception.ChatKickContendedException;
import com.sapari.chat.domain.exception.ChatKickEvidenceMismatchException;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 강퇴 엔드포인트에 <b>누가 도달하는가</b>를 고정한다.
 *
 * <p>지키는 것은 {@code LiveSecurityConfig} 의 규칙 <b>순서</b>다. 강퇴 규칙
 * ({@code hasAnyRole("SELLER","ADMIN")})은 판매자 전용 규칙({@code /api/v1/lives/**} →
 * {@code hasRole("SELLER")})보다 <b>먼저</b> 와야 하고, 시큐리티는 먼저 맞는 규칙이 이긴다.
 * 순서가 뒤집히면 관리자가 자기 방이 아닌 방을 통제하지 못한다 — 403 이 되므로 여기서 갈린다.
 *
 * <p>주석으로만 적힌 계약은 다음 사람이 규칙 하나를 위로 올리는 순간 조용히 깨진다. 그때 아무 테스트도
 * 실패하지 않으면, 관리자 모더레이션이 사라진 것을 아무도 모른 채로 배포된다.
 *
 * <p>여기서 재는 것은 <b>관문 통과 여부</b>뿐이다. 자기 방인지, 방이 진행 중인지, 대상이 관리자는
 * 아닌지는 {@code KickUserService} 가 보고 그쪽 테스트가 잰다. 그래서 use case 는 가짜다 —
 * 통과했는지를 "호출됐는가" 로 확인한다.
 */
// management.server.port 는 LiveSecurityConfig 의 가드 빈이 요구한다 — 없으면 액추에이터가
// 사용자 포트에 열린다며 컨텍스트가 뜨지 않는다. 여기서 재는 것과 무관하지만 채워야 뜬다.
@WebMvcTest(controllers = ChatModerationController.class,
        properties = {"management.server.port=9999",
                // 노출 가드가 prometheus 를 요구한다 — 빠지면 스크레이퍼가 401 을 받아
                // 지표가 조용히 빈다(LiveSecurityConfig.requiredEndpointsMustBeExposed).
                // 이 조각 테스트에는 액추에이터가 없어 그 가드가 건너뛰므로 사실 이 줄이 없어도 뜬다 —
                // 전에는 필요했고, 남겨 두면 운영 설정과 같은 모양이라는 정보가 된다.
                "management.endpoints.web.exposure.include=health,prometheus"})
// 컨트롤러를 명시로 등록한다. 아래 TestApplication 은 @SpringBootConfiguration 뿐이라 컴포넌트
// 스캔이 없고, 스캔이 없으면 @WebMvcTest 의 controllers 지정만으로는 빈이 생기지 않아 404 가 된다.
// GlobalExceptionHandler 를 올린다 — 근거는 형제 ChatHistoryControllerTest 에 적혀 있다
// (스캔이 없어 @RestControllerAdvice 가 안 잡히고, 없으면 ChatException 이 매핑되지 않는다).
@Import({ChatModerationController.class, LiveSecurityConfig.class, EnterRateLimitConfig.class,
        ChatKickAuthorizationTest.FixedClockConfig.class, GlobalExceptionHandler.class})
@DisplayName("강퇴 엔드포인트 — 판매자와 관리자만 관문을 지난다")
class ChatKickAuthorizationTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRINCIPAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TARGET_IN_BODY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String BODY = """
            {"targetUserId":"22222222-2222-2222-2222-222222222222","messageId":"665f1f77bcf86cd799439011"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KickUserUseCase kickUserUseCase;

    @MockitoBean
    private ReconcileKickListUseCase reconcileKickListUseCase;

    @MockitoBean
    private ClearRoomEndedMarkerUseCase clearRoomEndedMarkerUseCase;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * {@code LiveSecurityConfig} 의 {@code TokenRevocationFilter} 가 요구하는 둘. 이 테스트의 축은
     * 인가 규칙이라 기본 스텁(=폐기 아님)으로 둔다 — 목이 없으면 체인이 뜨지 않으므로 빼지 말 것.
     */
    @MockitoBean
    private AccessTokenRevocationChecker accessTokenRevocationChecker;

    @MockitoBean
    private SessionRevocationChecker sessionRevocationChecker;

    /** 실제 인증 필터를 태우지 않고 주체만 심는다 — 여기서 재는 것은 토큰 파싱이 아니라 인가 규칙이다. */
    private static RequestPostProcessor as(String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(PRINCIPAL_ID, UUID.randomUUID(), UUID.randomUUID(), role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
    }

    @Test
    @DisplayName("⭐ 도메인 예외가 선언한 상태코드로 HTTP 를 떠난다 — 서비스 테스트는 타입만 보고 이 구간을 아무도 잇지 않았다")
    void chatErrorCodesLeaveAsTheirDeclaredStatus() throws Exception {
        // given & when & then: 코드마다 상태를 따로 본다 — 하나만 보면 "전부 400" 같은 회귀가 통과한다.
        // 분모가 0 이었던 자리다: 이 슬라이스가 GlobalExceptionHandler 를 안 올려 ChatException 이
        // 매핑되지 않았고, 그래서 ChatErrorCode 의 상태를 뒤집어도 live-app 에서 아무것도 깨지지 않았다.
        assertKickFailureMapsTo(new ChatKickContendedException("경합", null),
                status().isConflict(), "CHAT-008");
        assertKickFailureMapsTo(new LiveNotActiveException("진행 중이 아니다"),
                status().isBadRequest(), "CHAT-004");
        assertKickFailureMapsTo(new ChatKickEvidenceMismatchException("증거 불일치"),
                status().isBadRequest(), "CHAT-006");
        assertKickFailureMapsTo(new ChatPermissionDeniedException("권한 없음"),
                status().isForbidden(), "CHAT-001");
    }

    /** 유스케이스가 그 예외를 던질 때 엔드포인트가 내는 상태와 코드. */
    private void assertKickFailureMapsTo(RuntimeException thrown, ResultMatcher expectedStatus,
            String expectedCode) throws Exception {
        willThrow(thrown).given(kickUserUseCase).kick(any());

        mockMvc.perform(post("/api/v1/lives/{roomId}/chat/kick", ROOM_ID)
                        .with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(expectedStatus)
                .andExpect(jsonPath("$.error.code").value(expectedCode));
    }

    @Test
    @DisplayName("⭐ 관리자는 지난다 — 강퇴 규칙이 판매자 전용 규칙보다 앞에 있어야만 성립한다")
    void adminPassesTheGate() throws Exception {
        // when & then: 규칙 순서가 뒤집히면 /api/v1/lives/** → hasRole("SELLER") 가 먼저 맞아 403 이 된다
        mockMvc.perform(post("/api/v1/lives/{roomId}/chat/kick", ROOM_ID)
                        .with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("판매자도 지난다 — 관리자를 넣느라 판매자를 밀어내지 않았다")
    void sellerPassesTheGate() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/lives/{roomId}/chat/kick", ROOM_ID)
                        .with(as("SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("일반 사용자는 막힌다 — 관문을 넓힌 것이 아니라 관리자만 더한 것이다")
    void buyerIsDenied() throws Exception {
        // when
        mockMvc.perform(post("/api/v1/lives/{roomId}/chat/kick", ROOM_ID)
                        .with(as("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        // then: 관문에서 끊겼으므로 서비스까지 가지 않는다
        then(kickUserUseCase).should(never()).kick(any());
    }

    /**
     * ⭐ 재적재는 <b>ADMIN 만</b>이다 — 강퇴 관문과 같은 컨트롤러인데 역할이 다르다.
     *
     * <p>경로가 방 아래({@code /api/v1/lives/*}/chat/kicked)라 판매자 전용 규칙에 걸린다. 그러니
     * 재적재 규칙이 그보다 <b>먼저</b> 와야 하고, 동시에 강퇴처럼 SELLER 를 포함하면 안 된다.
     * 두 조건이 반대 방향이라 순서가 뒤집히거나 역할이 넓어지면 둘 중 하나가 깨진다.
     *
     * <p>판매자에게 열지 않는 이유는 위험이 아니라 <b>가청성</b>이다 — 입력이 정본이라 과다 차단을
     * 새로 만들 수 없고, 이 조치가 필요하다는 신호는 {@code KickStoreCorruptedException} 로그이며
     * 그건 운영자가 읽는다.
     */
    @Test
    @DisplayName("⭐ 재적재는 관리자가 지난다 — 판매자 전용 규칙보다 앞에 있어야만 성립한다")
    void adminPassesTheReconcileGate() throws Exception {
        org.mockito.BDDMockito.given(reconcileKickListUseCase.reconcile(any()))
                .willReturn(new ReconcileKickListUseCase.ReconcileOutcome(3, false));
        // when & then: 규칙 순서가 뒤집히면 /api/v1/lives/** → hasRole("SELLER") 가 먼저 맞아 403 이 된다
        mockMvc.perform(put("/api/v1/lives/{roomId}/chat/kicked", ROOM_ID).with(as("ADMIN")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SELLER", "USER"})
    @DisplayName("⭐ 재적재는 판매자도 막는다 — 강퇴 관문을 그대로 베끼면 여기가 통과로 뒤집힌다")
    void nonAdminIsDeniedTheReconcileGate(String role) throws Exception {
        // when: 자기 방이어도 이 조치의 대상이 아니다 — 가청성이 기준이다(로그를 읽는 사람이 운영자다)
        mockMvc.perform(put("/api/v1/lives/{roomId}/chat/kicked", ROOM_ID).with(as(role)))
                .andExpect(status().isForbidden());

        // then: 관문에서 끊겼으므로 서비스까지 가지 않는다
        then(reconcileKickListUseCase).should(never()).reconcile(any());
    }

    /**
     * ⭐ 세 커맨드의 자리 배치를 한 자리에서 못 박는다.
     *
     * <p>{@code KickUserCommand}·{@code ReconcileKickListCommand}·{@code ClearRoomEndedMarkerCommand}
     * 가 모두 앞 둘이 {@code UUID} 라 <b>뒤집어도 컴파일된다</b>. 관문 테스트는 상태 코드만 보므로
     * 뒤집혀도 초록이고, 그 상태의 결과가 조치마다 다르게 틀린다 — 강퇴는 방 id 와 누른 사람이
     * 바뀌고, 마커 해제는 엉뚱한 방의 진위를 묻는다.
     *
     * <p>타입 래퍼로 막는 길도 있지만 이 저장소의 커맨드는 전부 평평한 {@code UUID} 라 한 군데만
     * 바꾸면 모양이 갈린다. 그래서 <b>경계에서 값의 출처</b>를 단언한다 — 경로 변수는 방·대상,
     * 인증 주체는 행위자다.
     */
    @Test
    @DisplayName("⭐ 경로 값이 방이고 인증 주체가 행위자다 — 세 커맨드 모두 뒤집어도 컴파일된다")
    void thePathValueIsTheRoomAndThePrincipalIsTheActor() throws Exception {
        // given
        org.mockito.BDDMockito.given(reconcileKickListUseCase.reconcile(any()))
                .willReturn(new ReconcileKickListUseCase.ReconcileOutcome(0, false));

        // when: 세 조치를 각각 한 번씩 부른다
        mockMvc.perform(post("/api/v1/lives/{roomId}/chat/kick", ROOM_ID).with(as("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
        mockMvc.perform(put("/api/v1/lives/{roomId}/chat/kicked", ROOM_ID).with(as("ADMIN")));
        mockMvc.perform(delete("/api/v1/lives/{roomId}/chat/ended", ROOM_ID).with(as("ADMIN")));

        // then
        var kick = org.mockito.ArgumentCaptor.forClass(com.sapari.chat.command.KickUserCommand.class);
        org.mockito.Mockito.verify(kickUserUseCase).kick(kick.capture());
        assertThat(kick.getValue().roomId()).as("강퇴의 방이 경로 값이 아니다").isEqualTo(ROOM_ID);
        assertThat(kick.getValue().kickerId()).as("강퇴한 사람이 인증 주체가 아니다").isEqualTo(PRINCIPAL_ID);
        assertThat(kick.getValue().targetUserId())
                .as("강퇴 대상이 본문 값이 아니다 — 주체와 대상이 뒤집히면 자기를 강퇴한다")
                .isEqualTo(TARGET_IN_BODY);

        var reconcile = org.mockito.ArgumentCaptor.forClass(
                com.sapari.chat.command.ReconcileKickListCommand.class);
        org.mockito.Mockito.verify(reconcileKickListUseCase).reconcile(reconcile.capture());
        assertThat(reconcile.getValue().roomId()).isEqualTo(ROOM_ID);
        assertThat(reconcile.getValue().actorId()).isEqualTo(PRINCIPAL_ID);

        var clear = org.mockito.ArgumentCaptor.forClass(
                com.sapari.chat.command.ClearRoomEndedMarkerCommand.class);
        org.mockito.Mockito.verify(clearRoomEndedMarkerUseCase).clear(clear.capture());
        assertThat(clear.getValue().roomId())
                .as("마커 해제의 방이 경로 값이 아니다 — 엉뚱한 방의 진위를 묻게 된다")
                .isEqualTo(ROOM_ID);
        assertThat(clear.getValue().actorId()).isEqualTo(PRINCIPAL_ID);
    }

    @Test
    @DisplayName("⭐ 종료 마커 해제는 판매자도 지난다 — 막힌 것이 자기 방송이고 먼저 알아챈다")
    void sellerPassesTheClearEndedGate() throws Exception {
        mockMvc.perform(delete("/api/v1/lives/{roomId}/chat/ended", ROOM_ID).with(as("SELLER")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("종료 마커 해제는 일반 사용자를 막는다")
    void buyerIsDeniedTheClearEndedGate() throws Exception {
        mockMvc.perform(delete("/api/v1/lives/{roomId}/chat/ended", ROOM_ID).with(as("USER")))
                .andExpect(status().isForbidden());

        then(clearRoomEndedMarkerUseCase).should(never()).clear(any());
    }

    @Test
    @DisplayName("비인증은 막힌다")
    void anonymousIsDenied() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/lives/{roomId}/chat/kick", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * {@code @WebMvcTest} 가 앱 클래스({@code LiveAppApplication})까지 거슬러 올라가지 않게 막는다.
     * 그대로 두면 앱의 {@code @EnableJpaRepositories} 가 함께 켜져 EntityManagerFactory 가 없다며
     * 컨텍스트가 통째로 못 뜬다 — 인가 규칙을 재려고 JPA 전체를 세울 이유가 없다.
     */
    @SpringBootConfiguration
    static class TestApplication {
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC));
        }
    }
}
