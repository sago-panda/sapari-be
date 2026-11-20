package com.sapari.liveapp.controller.live;

import static com.sapari.liveapp.controller.live.LiveSellerControllerTest.expectSuccessEnvelope;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.web.security.CurrentUserIdArgumentResolver;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.GetRecentLivesQuery;
import com.sapari.live.port.GetRecentLivesUseCase;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.RecentLiveView;
import com.sapari.liveapp.security.LiveUserPrincipal;

/** 최근 본 라이브 응답 계약. 인가는 {@code RecentLiveAuthorizationTest} 가 본다. */
@DisplayName("최근 본 라이브 엔드포인트 — 봉투·노출 필드·커서 400")
class RecentLiveControllerTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final GetRecentLivesUseCase useCase = mock(GetRecentLivesUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-10T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(new RecentLiveController(useCase))
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider))
                .build();
        LiveUserPrincipal principal = new LiveUserPrincipal(
                USER_ID, UUID.randomUUID(), UUID.randomUUID(), "USER", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("200 — 공개 필드만, 송출 정보 키는 없다")
    void returnsPublicFieldsOnly() throws Exception {
        given(useCase.getRecent(any())).willReturn(new CursorPage<>(List.of(new RecentLiveView(
                ROOM_ID, SELLER_ID, "판매자", "제목", "https://cdn/t.jpg", LiveRoomTab.ENDED,
                Instant.parse("2026-11-09T11:00:00Z"), Instant.parse("2026-11-09T11:05:12.654321Z"))),
                "next", true));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/recent")).andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.content[0].roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].sellerId").value(SELLER_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.data.content[0].title").value("제목"))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://cdn/t.jpg"))
                .andExpect(jsonPath("$.data.content[0].status").value("ENDED"))
                .andExpect(jsonPath("$.data.content[0].startedAt").exists())
                .andExpect(jsonPath("$.data.content[0].viewedAt").exists())
                .andExpect(jsonPath("$.data.content[0]", not(hasKey("hlsUrl"))))
                .andExpect(jsonPath("$.data.content[0]", not(hasKey("roomToken"))))
                .andExpect(jsonPath("$.data.content[0]", not(hasKey("rtmpUrl"))))
                .andExpect(jsonPath("$.data.nextCursor").value("next"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("cursor·size 와 인증 주체가 질의로 전달된다")
    void passesQuery() throws Exception {
        given(useCase.getRecent(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mvc.perform(get("/api/v1/lives/recent").param("cursor", "abc").param("size", "5"))
                .andExpect(status().isOk());

        then(useCase).should().getRecent(new GetRecentLivesQuery(USER_ID, "abc", 5));
    }

    @Test
    @DisplayName("깨진 커서 — 400 봉투")
    void invalidCursorIs400() throws Exception {
        given(useCase.getRecent(any())).willThrow(new InvalidCursorException("bad"));

        mvc.perform(get("/api/v1/lives/recent").param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").exists());
    }
}
