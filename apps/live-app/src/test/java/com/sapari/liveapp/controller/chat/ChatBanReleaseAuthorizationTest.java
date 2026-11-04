package com.sapari.liveapp.controller.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.sapari.chat.port.ReleaseBanUseCase;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;
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
@Import({ChatBanController.class, LiveSecurityConfig.class,
        ChatBanReleaseAuthorizationTest.FixedClockConfig.class})
@DisplayName("밴 해제 엔드포인트 — 관리자만 관문을 지난다")
class ChatBanReleaseAuthorizationTest {

    private static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReleaseBanUseCase releaseBanUseCase;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    /** 실제 인증 필터를 태우지 않고 주체만 심는다 — 재는 것은 토큰 파싱이 아니라 인가 규칙이다. */
    private static RequestPostProcessor as(String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
    }

    @Test
    @DisplayName("⭐ 관리자는 지난다 — 성공은 204, 응답 본문이 없다")
    void adminPassesTheGate() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/bans/{userId}", TARGET).with(as("ADMIN")))
                .andExpect(status().isNoContent());
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
