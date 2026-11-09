package com.sapari.liveapp.controller.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sapari.chat.command.ReconcileBanMirrorCommand;
import com.sapari.chat.port.ReconcileBanMirrorUseCase;
import com.sapari.chat.port.ReleaseBanUseCase;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.config.EnterRateLimitConfig;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 밴 해제 엔드포인트에 <b>누가 도달하는가</b>를 고정한다.
 *
 * <p>강퇴와 다른 축이라 따로 잰다. 강퇴 관문은 {@code hasAnyRole("SELLER","ADMIN")} 인데 해제는
 * <b>ADMIN 전용</b>이다 — 자동 승격은 서로 다른 판매자 3·6·9명의 독립된 판단으로 걸리므로,
 * 그중 한 명이 혼자 되돌릴 수 있으면 그 임계가 요구한 독립성이 무의미해진다.
 *
 * <p>경로가 방 밖({@code /api/v1/chat/bans/**})이라 <b>판매자 전용 규칙에 걸리지 않는다</b>.
 * 그래서 명시 규칙이 없으면 {@code anyRequest().authenticated()} 로 떨어지는데, 그 줄은 역할을
 * 보지 않아 <b>구매자도 통과</b>한다. 그때 거부는 서비스의 권한 검사 하나에만 걸린다 —
 * 관문 하나가 사라진 것을 아무 테스트도 말해 주지 않는 상태가 되므로 여기서 못 박는다.
 */
@WebMvcTest(controllers = ChatBanController.class,
        properties = {"management.server.port=9999",
                "management.endpoints.web.exposure.include=health,prometheus"})
// GlobalExceptionHandler 를 올린다 — 근거는 형제 ChatHistoryControllerTest 에 적혀 있다
// (스캔이 없어 @RestControllerAdvice 가 안 잡히고, 없으면 ChatException 이 매핑되지 않는다).
@Import({ChatBanController.class, LiveSecurityConfig.class, EnterRateLimitConfig.class,
        ChatBanReleaseAuthorizationTest.FixedClockConfig.class, GlobalExceptionHandler.class})
@DisplayName("밴 해제 엔드포인트 — 관리자만 관문을 지난다")
class ChatBanReleaseAuthorizationTest {

    private static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PRINCIPAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReleaseBanUseCase releaseBanUseCase;

    @MockitoBean
    private ReconcileBanMirrorUseCase reconcileBanMirrorUseCase;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * {@code LiveSecurityConfig} 의 {@code TokenRevocationFilter} 가 요구하는 둘. 이 테스트의 축은
     * 인가 규칙이라 기본 스텁(=폐기 아님)으로 둔다 — 목이 없으면 체인이 뜨지 않으므로 빼지 말 것.
     *
     * <p>⚠️ <b>여기에 "폐기된 ADMIN 은 막힌다" 칸을 두려 했는데 이 하네스로는 잴 수 없다.</b>
     * {@code authentication(auth)} 가 심는 인증이 체인의 커스텀 필터에는 보이지 않는다 —
     * 실측: 그 필터에서 {@code getAuthentication()} 이 7개 요청 전부 {@code null} 인데
     * {@code AuthorizationFilter} 는 ADMIN 을 보고 200 을 돌려줬다. 즉 이 슬라이스는 인가 규칙만
     * 재고 필터 순서는 재지 못한다. 순서는 {@code LiveFilterOrderTest} 가 체인에서, 동작은
     * {@code TokenRevocationFilterTest} 가 필터에서 잰다.
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
    @DisplayName("⭐ 권한 거부가 선언한 상태코드로 HTTP 를 떠난다 — 관문이 아니라 서비스가 접은 경우다")
    void thePermissionDeniedCodeLeavesAsForbidden() throws Exception {
        // given: 관문은 통과했고(ADMIN) 서비스가 접었다 — 관문 403 과 같은 상태지만 코드가 다르다
        willThrow(new ChatPermissionDeniedException("알 수 없는 역할이다"))
                .given(releaseBanUseCase).release(any());

        // when & then
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET).with(as("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHAT-001"));
    }

    @Test
    @DisplayName("⭐ 관리자는 지난다 — 성공은 200 + 본문(무엇을 풀었는가)")
    void adminPassesTheGate() throws Exception {
        // given: 활성 영구 밴을 풀었다
        given(releaseBanUseCase.release(any()))
                .willReturn(new ReleaseBanUseCase.ReleaseBanResult(true, true));

        // when & then: 204 로 두면 "활성 영구 밴을 풀었다" 와 "밴 없는 사람에게 눌렀다" 가 같은 응답이다
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET).with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.banExisted").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.wasPermanent").value(true));
    }

    @Test
    @DisplayName("⭐ 풀 밴이 없었으면 그 사실이 본문에 온다 — 오타 한 글자로 남의 누적을 접은 경우")
    void theResponseSaysWhenThereWasNoBan() throws Exception {
        // given
        given(releaseBanUseCase.release(any()))
                .willReturn(new ReleaseBanUseCase.ReleaseBanResult(false, false));

        // when & then: 존재 여부를 제3자에게 누설하지 않되, 누른 사람에게는 보여야 한다
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET).with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.banExisted").value(false));
    }

    /**
     * ⭐ 커맨드의 <b>어느 자리에 무엇이 들어가는지</b>를 못 박는다.
     *
     * <p>{@code ReleaseBanCommand(targetUserId, actorId, actorRole)} 의 앞 둘이 모두 {@code UUID}
     * 라 <b>뒤집어도 컴파일된다.</b> 그리고 위 관문 테스트는 204 만 보므로 뒤집혀도 초록이다 —
     * 그 상태의 결과는 "해제를 누른 관리자 자신의 밴이 풀리고 대상은 그대로 막혀 있는" 것이고,
     * 그게 로그와 {@code chat_ban_release} 행에도 관리자 id 로 남아 추적까지 어긋난다.
     *
     * <p>타입으로 막는 길(래퍼 레코드)도 있지만 이 저장소의 커맨드는 전부 평평한 {@code UUID} 라
     * 한 군데만 바꾸면 모양이 갈린다. 그래서 <b>경계에서 값의 출처를 단언</b>하는 쪽을 골랐다 —
     * 경로 변수는 대상, 인증 주체는 행위자다.
     */
    @Test
    @DisplayName("⭐ 경로 값이 대상이고 인증 주체가 행위자다 — 둘 다 UUID 라 뒤집어도 컴파일된다")
    void thePathValueIsTheTargetAndThePrincipalIsTheActor() throws Exception {
        // given
        given(releaseBanUseCase.release(any()))
                .willReturn(new ReleaseBanUseCase.ReleaseBanResult(true, false));

        // when
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET).with(as("ADMIN")))
                .andExpect(status().isOk());

        // then
        org.mockito.ArgumentCaptor<com.sapari.chat.command.ReleaseBanCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.sapari.chat.command.ReleaseBanCommand.class);
        org.mockito.Mockito.verify(releaseBanUseCase).release(captor.capture());

        assertThat(captor.getValue().targetUserId())
                .as("대상이 경로 값이 아니다 — 뒤집히면 누른 사람의 밴이 풀린다")
                .isEqualTo(TARGET);
        assertThat(captor.getValue().actorId())
                .as("행위자가 인증 주체가 아니다")
                .isEqualTo(PRINCIPAL_ID);
        assertThat(captor.getValue().actorRole()).isEqualTo("ADMIN");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"USER", "SELLER"})
    @DisplayName("⭐ 판매자도 막힌다 — anyRequest().authenticated() 로 떨어지면 여기가 통과로 뒤집힌다")
    void nonAdminIsDeniedAtTheGate(String role) throws Exception {
        // when
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET).with(as(role)))
                .andExpect(status().isForbidden());

        // then: 관문에서 끊겼으므로 서비스까지 가지 않는다
        then(releaseBanUseCase).should(never()).release(any());
    }

    /** 미러 재적재도 같은 {@code /chat/bans/**} 규칙 아래다 — 경로를 옮기면 그 규칙에서 빠진다. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"USER", "SELLER"})
    @DisplayName("⭐ 미러 재적재도 관리자만 — 판매자·구매자는 관문에서 끊긴다")
    void nonAdminIsDeniedAtTheMirrorGate(String role) throws Exception {
        mockMvc.perform(put("/api/v1/chat/bans/mirror").with(as(role)))
                .andExpect(status().isForbidden());

        then(reconcileBanMirrorUseCase).should(never()).reconcile(any());
    }

    @Test
    @DisplayName("관리자는 미러 재적재에 닿고, 행위자는 인증 주체다")
    void adminReachesTheMirrorReconcile() throws Exception {
        given(reconcileBanMirrorUseCase.reconcile(any()))
                .willReturn(new ReconcileBanMirrorUseCase.ReconcileOutcome(3, 1));

        mockMvc.perform(put("/api/v1/chat/bans/mirror").with(as("ADMIN")))
                .andExpect(status().isOk());

        ArgumentCaptor<ReconcileBanMirrorCommand> captor = ArgumentCaptor.forClass(ReconcileBanMirrorCommand.class);
        then(reconcileBanMirrorUseCase).should().reconcile(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(PRINCIPAL_ID);
        assertThat(captor.getValue().actorRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("비인증은 막힌다")
    void anonymousIsDenied() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET))
                .andExpect(status().isUnauthorized());
    }

    /**
     * {@code @WebMvcTest} 가 앱 클래스까지 거슬러 올라가지 않게 막는다 — 그대로 두면 앱의
     * {@code @EnableJpaRepositories} 가 함께 켜져 EntityManagerFactory 가 없다며 컨텍스트가 못 뜬다.
     */
    @SpringBootConfiguration
    static class TestApplication {
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneOffset.UTC));
        }
    }
}
