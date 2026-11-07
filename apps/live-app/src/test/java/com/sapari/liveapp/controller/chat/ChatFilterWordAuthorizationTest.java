package com.sapari.liveapp.controller.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sapari.chat.port.ManageFilterWordsUseCase;
import com.sapari.chat.view.FilterWordsView;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.chat.domain.exception.FilterWordRejectedException;
import com.sapari.chat.domain.exception.FilterWordSourceInvalidException;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.config.EnterRateLimitConfig;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 사전 관리 엔드포인트에 <b>누가 도달하는가</b>를 고정한다 — 세 메서드 전부.
 *
 * <p>경로가 방 밖({@code /api/v1/chat/filter-words})이라 판매자 전용 규칙에 걸리지 않는다.
 * 명시 규칙이 없으면 {@code anyRequest().authenticated()} 로 떨어지는데 그 줄은 역할을 보지
 * 않아 <b>구매자도 통과</b>한다 — 밴 해제에서 같은 모양을 이미 겪었다.
 *
 * <p><b>GET 을 함께 재는 것이 요점이다.</b> 쓰기만 ADMIN 으로 두고 읽기를 빠뜨리면 구매자가
 * "플랫폼이 무엇을 막는가" 의 전체 목록을 받는다 — 우회하려는 사람에게는 어떤 표기가 사전에
 * <b>없는지</b>를 알려 주는 값이다. 그래서 세 메서드를 같은 벡터로 돌린다.
 *
 * <p>관문이 패턴 하나({@code /filter-words/**})로 덮이는지도 여기서 확인된다 — 그 패턴이
 * 하위 세그먼트 없는 경로 자신에도 맞는다는 것은 실측으로 확인했고(PathPattern·AntPathMatcher
 * 둘 다 참), 이 테스트가 그 실측을 실제 보안 체인에서 재확인한다.
 */
@WebMvcTest(controllers = ChatFilterWordController.class,
        properties = {"management.server.port=9999",
                "management.endpoints.web.exposure.include=health,prometheus"})
// GlobalExceptionHandler 를 올린다 — 이 슬라이스에는 컴포넌트 스캔이 없어 @RestControllerAdvice 가
// 자동으로 잡히지 않는다. 없으면 ChatException 이 매핑된 상태가 아니라 ServletException 으로 나와,
// 에러코드가 선언한 상태코드로 HTTP 를 떠나는지 **아무 테스트도 단언하지 못한다**.
// SPR-178 전에는 chat 컨트롤러 테스트 넷 중 하나만 이것을 올렸고(실측), 그래서 CHAT-001/004/006/
// 008/010/011 이 선언한 상태로 HTTP 를 떠나는지가 이 모듈에서 단언되지 않았다 — 역변이로 확인했다:
// CHAT-011 을 409 → 400 으로 뒤집어도 live-app 에서 아무것도 깨지지 않았다.
@Import({GlobalExceptionHandler.class, ChatFilterWordController.class, LiveSecurityConfig.class, EnterRateLimitConfig.class,
        ChatFilterWordAuthorizationTest.FixedClockConfig.class})
@DisplayName("사전 관리 엔드포인트 — 세 메서드 전부 관리자만 지난다")
class ChatFilterWordAuthorizationTest {

    private static final UUID PRINCIPAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManageFilterWordsUseCase manageFilterWordsUseCase;

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

    /** 실제 인증 필터를 태우지 않고 주체만 심는다 — 재는 것은 토큰 파싱이 아니라 인가 규칙이다. */
    private static RequestPostProcessor as(String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(PRINCIPAL_ID, UUID.randomUUID(), UUID.randomUUID(), role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
    }

    @Test
    @DisplayName("⭐ 사전 에러코드가 선언한 상태코드로 HTTP 를 떠난다 — 409 를 400 으로 뒤집어도 여태 아무것도 깨지지 않았다")
    void filterWordErrorCodesLeaveAsTheirDeclaredStatus() throws Exception {
        // given: 409 와 400 을 같이 본다 — 하나만 보면 "전부 400" 회귀가 통과한다
        willThrow(new FilterWordSourceInvalidException("출처가 다르다", null))
                .given(manageFilterWordsUseCase).add(any());

        // when & then
        mockMvc.perform(post("/api/v1/chat/filter-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROFANITY\",\"word\":\"지랄\"}")
                        .with(as("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHAT-011"));

        willThrow(new FilterWordRejectedException("형식이 아니다"))
                .given(manageFilterWordsUseCase).add(any());

        mockMvc.perform(post("/api/v1/chat/filter-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROFANITY\",\"word\":\"지랄\"}")
                        .with(as("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHAT-010"));
    }

    @Test
    @DisplayName("⭐ 관리자는 목록을 읽는다")
    void adminMayList() throws Exception {
        given(manageFilterWordsUseCase.list(any()))
                .willReturn(new FilterWordsView(Set.of("시발"), Set.of("시발점")));

        mockMvc.perform(get("/api/v1/chat/filter-words").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.words[0]").value("시발"))
                .andExpect(jsonPath("$.data.whitelist[0]").value("시발점"));
    }

    /**
     * ⭐ 성공이 201 이 아니라 200 인 이유: 멱등이라 "새로 만들어졌다" 가 보장되지 않는다.
     * 무엇이 일어났는지는 {@code changed} 가 말하고, 함대에 깔릴 번호는 {@code version} 이다.
     */
    @Test
    @DisplayName("⭐ 관리자는 더할 수 있다 — 200 + changed·version")
    void adminMayAdd() throws Exception {
        given(manageFilterWordsUseCase.add(any()))
                .willReturn(new ManageFilterWordsUseCase.FilterWordChange(true, 7L));

        mockMvc.perform(post("/api/v1/chat/filter-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROFANITY\",\"word\":\"지랄\"}")
                        .with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.version").value(7));
    }

    @Test
    @DisplayName("⭐ 관리자는 뺄 수 있다 — 쿼리 파라미터로 받는다(DELETE 본문은 프록시가 뗀다)")
    void adminMayRemove() throws Exception {
        given(manageFilterWordsUseCase.remove(any()))
                .willReturn(new ManageFilterWordsUseCase.FilterWordChange(true, 8L));

        mockMvc.perform(delete("/api/v1/chat/filter-words")
                        .param("kind", "PROFANITY").param("word", "지랄")
                        .with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8));
    }

    /**
     * ⭐ 세 메서드를 같은 벡터로 돌린다. 하나만 재면 나머지가 폴스루로 떨어진 것을 아무도
     * 말해 주지 않는다 — 특히 <b>GET</b>: 쓰기만 막고 읽기를 빠뜨리는 것이 이 모양에서 가장
     * 하기 쉬운 실수이고, 그러면 구매자가 사전 전체를 받는다.
     */
    @ParameterizedTest(name = "role={0}")
    @ValueSource(strings = {"SELLER", "USER"})
    @DisplayName("⭐ 관리자가 아니면 세 메서드 모두 관문에서 막힌다 — GET 포함")
    void othersAreBlockedOnAllThreeMethods(String role) throws Exception {
        mockMvc.perform(get("/api/v1/chat/filter-words").with(as(role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/chat/filter-words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROFANITY\",\"word\":\"지랄\"}")
                        .with(as(role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/chat/filter-words")
                        .param("kind", "PROFANITY").param("word", "지랄")
                        .with(as(role)))
                .andExpect(status().isForbidden());

        // 관문에서 막혔으면 유스케이스에 닿지 않는다 — 서비스의 권한 검사가 두 번째 겹이고,
        // 이 단언은 <첫 겹이 살아 있는지>를 본다.
        then(manageFilterWordsUseCase).should(never()).list(any());
        then(manageFilterWordsUseCase).should(never()).add(any());
        then(manageFilterWordsUseCase).should(never()).remove(any());
    }

    @Test
    @DisplayName("인증이 없으면 401 이다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/chat/filter-words"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * {@code @WebMvcTest} 가 앱 클래스까지 거슬러 올라가지 않게 막는다 — 그대로 두면 앱의
     * {@code @EnableJpaRepositories} 가 함께 켜져 EntityManagerFactory 가 없다며 컨텍스트가 못 뜬다.
     *
     * <p>이 패키지의 형제 테스트들이 각자 하나씩 들고 있다. 빼면 Spring 이 위로 올라가다 그
     * 여럿을 발견하고 {@code Found multiple @SpringBootConfiguration annotated classes} 로
     * 터진다 — 처음에 빠뜨려서 실제로 그렇게 됐다.
     */
    @SpringBootConfiguration
    static class TestApplication {
    }

    /**
     * {@code LiveSecurityConfig} 의 인증 실패 엔트리포인트가 {@link TimeProvider} 를 요구한다 —
     * 없으면 보안 체인 자체가 못 뜬다. 이 테스트는 시간을 재지 않지만 고정 시계를 주는 것은
     * 형제 테스트와 같은 습관이다(시스템 시계로 두면 재지 않는 값이 테스트에 들어온다).
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneOffset.UTC));
        }
    }
}
