package com.sapari.liveapp.controller.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.sapari.live.port.CancelLiveUseCase;
import com.sapari.live.port.GetLiveRoomDetailUseCase;
import com.sapari.live.port.GetSellerRoomsUseCase;
import com.sapari.live.port.UpdateLiveUseCase;
import com.sapari.liveapp.config.EnterRateLimitConfig;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 방 관리 엔드포인트의 관문 — 판매자 전용 경로에 구매자(USER) 토큰이 403 을 받는다.
 *
 * <p>익명 401 은 {@code LivePublicRoutesTest} 가 본다. 여기서는 <b>인증은 됐지만 역할이 다른</b> 주체를 재는데,
 * 이 경로들은 전용 규칙 없이 {@code /api/v1/lives/**} 의 SELLER 바닥에만 기대므로 그 바닥이 실제로 걸리는지를
 * 고정한다. 소유권(남의 방)은 쿼리가 보고 서비스 테스트가 잰다. 구조는 {@code ChatKickAuthorizationTest} 와 같다.
 */
@WebMvcTest(controllers = LiveRoomManageController.class,
        properties = {"management.server.port=9999",
                "management.endpoints.web.exposure.include=health,prometheus"})
@Import({LiveRoomManageController.class, LiveSecurityConfig.class, EnterRateLimitConfig.class,
        LiveRoomManageAuthorizationTest.FixedClockConfig.class})
@DisplayName("방 관리 엔드포인트 — 판매자 경로는 구매자에게 403")
class LiveRoomManageAuthorizationTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String UPDATE_BODY = """
            {"title":"새 제목","description":"새 설명","thumbnailUrl":"https://cdn/t2.jpg",
             "scheduledAt":"2026-11-10T09:00:00Z"}
            """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CancelLiveUseCase cancelLiveUseCase;
    @MockitoBean private UpdateLiveUseCase updateLiveUseCase;
    @MockitoBean private GetSellerRoomsUseCase getSellerRoomsUseCase;
    @MockitoBean private GetLiveRoomDetailUseCase getLiveRoomDetailUseCase;
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

    @Test
    @DisplayName("구매자 — 취소·수정은 403, 유스케이스에 닿지 않는다")
    void buyerCannotCancelOrUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/lives/rooms/{roomId}/cancel", ROOM_ID).with(as("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/lives/rooms/{roomId}", ROOM_ID).with(as("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden());

        then(cancelLiveUseCase).should(never()).cancel(any());
        then(updateLiveUseCase).should(never()).update(any());
    }

    @Test
    @DisplayName("구매자 — /seller/** 목록·개수·상세는 403")
    void buyerCannotReadSellerRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/lives/seller/rooms").with(as("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lives/seller/rooms/counts").with(as("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lives/seller/rooms/{roomId}", ROOM_ID).with(as("USER")))
                .andExpect(status().isForbidden());

        then(getSellerRoomsUseCase).should(never()).getRooms(any());
        then(getSellerRoomsUseCase).should(never()).getCounts(any());
        then(getLiveRoomDetailUseCase).should(never()).getSellerDetail(any());
    }

    @Test
    @DisplayName("판매자 — 같은 경로를 지난다(관문이 판매자까지 막지 않는다)")
    void sellerPasses() throws Exception {
        mockMvc.perform(post("/api/v1/lives/rooms/{roomId}/cancel", ROOM_ID).with(as("SELLER")))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/lives/rooms/{roomId}", ROOM_ID).with(as("SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/lives/seller/rooms/counts").with(as("SELLER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("구매자 — 공개 상세는 지난다")
    void buyerCanReadPublicDetail() throws Exception {
        mockMvc.perform(get("/api/v1/lives/rooms/{roomId}/detail", ROOM_ID).with(as("USER")))
                .andExpect(status().isOk());
    }

    @SpringBootConfiguration
    static class TestApplication {
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        TimeProvider timeProvider() {
            return new TimeProvider(Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC));
        }
    }
}
