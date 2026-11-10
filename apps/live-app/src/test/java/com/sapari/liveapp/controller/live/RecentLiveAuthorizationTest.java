package com.sapari.liveapp.controller.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import com.sapari.common.page.CursorPage;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.web.config.CommonWebMvcConfig;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.GetRecentLivesQuery;
import com.sapari.live.port.GetRecentLivesUseCase;
import com.sapari.liveapp.config.EnterRateLimitConfig;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 최근 본 라이브의 관문 — <b>구매자도 쓰는 회원 경로</b>라 {@code /api/v1/lives/**} 의 SELLER 바닥에 걸리면 안 되고,
 * 익명은 401 이어야 한다. 구조는 {@code LiveRoomManageAuthorizationTest} 와 같다. 그쪽과 달리 인증 주체가 질의로
 * 넘어가는지도 보므로 {@code @CurrentUserId} 리졸버를 등록하는 {@code CommonWebMvcConfig} 를 함께 올린다.
 */
@WebMvcTest(controllers = RecentLiveController.class,
        properties = {"management.server.port=9999",
                "management.endpoints.web.exposure.include=health,prometheus"})
@Import({RecentLiveController.class, LiveSecurityConfig.class, EnterRateLimitConfig.class, CommonWebMvcConfig.class,
        RecentLiveAuthorizationTest.FixedClockConfig.class})
@DisplayName("최근 본 라이브 — 구매자·판매자 200, 익명 401")
class RecentLiveAuthorizationTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private GetRecentLivesUseCase getRecentLivesUseCase;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    // TokenRevocationFilter 가 요구한다 — 기본 스텁(=폐기 아님)이라 인가 판정을 가리지 않는다.
    @MockitoBean private AccessTokenRevocationChecker accessTokenRevocationChecker;
    @MockitoBean private SessionRevocationChecker sessionRevocationChecker;

    private static RequestPostProcessor as(UUID userId, String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(
                userId, UUID.randomUUID(), UUID.randomUUID(), role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
    }

    @Test
    @DisplayName("구매자 — 200, 인증 주체의 userId 로 조회한다")
    void buyerPasses() throws Exception {
        UUID buyerId = UUID.randomUUID();
        given(getRecentLivesUseCase.getRecent(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mockMvc.perform(get("/api/v1/lives/recent").with(as(buyerId, "USER"))).andExpect(status().isOk());

        then(getRecentLivesUseCase).should().getRecent(new GetRecentLivesQuery(buyerId, null, null));
    }

    @Test
    @DisplayName("판매자 — 200")
    void sellerPasses() throws Exception {
        given(getRecentLivesUseCase.getRecent(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mockMvc.perform(get("/api/v1/lives/recent").with(as(UUID.randomUUID(), "SELLER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("익명 — 401, 유스케이스에 닿지 않는다")
    void anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/v1/lives/recent")).andExpect(status().isUnauthorized());

        then(getRecentLivesUseCase).should(never()).getRecent(any());
    }

    @SpringBootConfiguration
    static class TestApplication {
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
        }
    }
}
