package com.sapari.liveapp.controller.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.AdminEndLiveUseCase;
import com.sapari.live.port.ChangeSellerLivePermissionUseCase;
import com.sapari.liveapp.config.EnterRateLimitConfig;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.ChatReadRateLimitFixtures;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 관리자 조치(종료·판매자 라이브 권한)의 관문 — {@code /api/v1/lives/admin/**} 는 ADMIN 전용이다.
 *
 * <p>이 경로는 {@code /api/v1/lives/**} 의 SELLER 바닥 아래에 있으므로, 전용 규칙이 없거나 그보다 뒤에
 * 오면 판매자가 남의 방을 끝낼 수 있고 관리자는 403 을 받는다. 그 순서를 고정한다.
 */
@WebMvcTest(controllers = LiveAdminController.class,
        properties = {"management.server.port=9999",
                "management.endpoints.web.exposure.include=health,prometheus"})
@Import({LiveAdminController.class, LiveSecurityConfig.class, EnterRateLimitConfig.class, ChatReadRateLimitFixtures.UnlimitedConfig.class,
        LiveAdminAuthorizationTest.FixedClockConfig.class})
@DisplayName("관리자 엔드포인트 — ADMIN 만 지난다")
class LiveAdminAuthorizationTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BODY = """
            {"reason":"정책 위반"}
            """;
    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PERMISSION_BODY = """
            {"liveEnabled":false,"reason":"정책 위반"}
            """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminEndLiveUseCase adminEndLiveUseCase;
    @MockitoBean private ChangeSellerLivePermissionUseCase changeSellerLivePermissionUseCase;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    // TokenRevocationFilter 가 요구한다 — 기본 스텁(=폐기 아님)이라 인가 판정을 가리지 않는다.
    @MockitoBean private AccessTokenRevocationChecker accessTokenRevocationChecker;
    @MockitoBean private SessionRevocationChecker sessionRevocationChecker;

    private static RequestPostProcessor as(String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
    }

    private org.springframework.test.web.servlet.ResultActions forceEnd(String role) throws Exception {
        return mockMvc.perform(post("/api/v1/lives/admin/rooms/{roomId}/force-end", ROOM_ID).with(as(role))
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    private org.springframework.test.web.servlet.ResultActions changePermission(String role) throws Exception {
        return mockMvc.perform(patch("/api/v1/lives/admin/sellers/{sellerId}/live-permission", SELLER_ID).with(as(role))
                .contentType(MediaType.APPLICATION_JSON).content(PERMISSION_BODY));
    }

    @Test
    @DisplayName("판매자 — 403, 유스케이스에 닿지 않는다(SELLER 바닥이 먼저 걸리면 남의 방을 끝낸다)")
    void sellerIsForbidden() throws Exception {
        forceEnd("SELLER").andExpect(status().isForbidden());

        then(adminEndLiveUseCase).should(never()).end(any());
    }

    @Test
    @DisplayName("구매자 — 403, 유스케이스에 닿지 않는다")
    void buyerIsForbidden() throws Exception {
        forceEnd("USER").andExpect(status().isForbidden());

        then(adminEndLiveUseCase).should(never()).end(any());
    }

    @Test
    @DisplayName("관리자 — 204")
    void adminPasses() throws Exception {
        forceEnd("ADMIN").andExpect(status().isNoContent());

        then(adminEndLiveUseCase).should(times(1)).end(any());
    }

    @Test
    @DisplayName("권한 변경 — 판매자 403(자기 권한을 다시 켤 수 없다), 구매자 403, 유스케이스에 닿지 않는다")
    void changePermission_nonAdminIsForbidden() throws Exception {
        changePermission("SELLER").andExpect(status().isForbidden());
        changePermission("USER").andExpect(status().isForbidden());

        then(changeSellerLivePermissionUseCase).should(never()).change(any());
    }

    @Test
    @DisplayName("권한 변경 — 관리자 204")
    void changePermission_adminPasses() throws Exception {
        changePermission("ADMIN").andExpect(status().isNoContent());

        then(changeSellerLivePermissionUseCase).should(times(1)).change(any());
    }

    @SpringBootConfiguration
    static class TestApplication {
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));
        }
    }
}
