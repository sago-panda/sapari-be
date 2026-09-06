package com.sapari.liveapp.controller.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.sapari.chat.port.KickUserUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;

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
        properties = "management.server.port=9999")
// 컨트롤러를 명시로 등록한다. 아래 TestApplication 은 @SpringBootConfiguration 뿐이라 컴포넌트
// 스캔이 없고, 스캔이 없으면 @WebMvcTest 의 controllers 지정만으로는 빈이 생기지 않아 404 가 된다.
@Import({ChatModerationController.class, LiveSecurityConfig.class,
        ChatKickAuthorizationTest.FixedClockConfig.class})
@DisplayName("강퇴 엔드포인트 — 판매자와 관리자만 관문을 지난다")
class ChatKickAuthorizationTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BODY = """
            {"targetUserId":"22222222-2222-2222-2222-222222222222","messageId":"665f1f77bcf86cd799439011"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KickUserUseCase kickUserUseCase;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    /** 실제 인증 필터를 태우지 않고 주체만 심는다 — 여기서 재는 것은 토큰 파싱이 아니라 인가 규칙이다. */
    private static RequestPostProcessor as(String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
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
