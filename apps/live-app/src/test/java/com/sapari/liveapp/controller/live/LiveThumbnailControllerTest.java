package com.sapari.liveapp.controller.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.web.security.CurrentUserIdArgumentResolver;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.IssueThumbnailUploadCommand;
import com.sapari.live.port.IssueThumbnailUploadUseCase;
import com.sapari.live.view.ThumbnailUploadView;
import com.sapari.liveapp.security.LiveUserPrincipal;

/** 썸네일 업로드 허가 — 봉투·상태 코드·입력 검증. 인가(SELLER)는 LiveSecurityConfig 테스트 몫이다. */
@DisplayName("썸네일 업로드 허가 엔드포인트")
class LiveThumbnailControllerTest {

    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String URL = "/api/v1/lives/thumbnails/uploads";

    private final IssueThumbnailUploadUseCase useCase = mock(IssueThumbnailUploadUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-08T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(new LiveThumbnailController(useCase))
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider))
                .build();
        LiveUserPrincipal principal = new LiveUserPrincipal(
                SELLER_ID, UUID.randomUUID(), UUID.randomUUID(), "SELLER", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_SELLER"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("201 + 봉투, sellerId 는 인증 주체에서")
    void issues() throws Exception {
        given(useCase.issue(any())).willReturn(new ThumbnailUploadView(
                "https://b.s3.r.amazonaws.com/", Map.of("key", "uploads/thumbnails/x/y"),
                Instant.parse("2026-11-08T00:10:00Z"), "https://cdn/thumbnails/x/y"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/png\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://b.s3.r.amazonaws.com/"))
                .andExpect(jsonPath("$.data.fields.key").value("uploads/thumbnails/x/y"))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.thumbnailUrl").value("https://cdn/thumbnails/x/y"));

        ArgumentCaptor<IssueThumbnailUploadCommand> command = ArgumentCaptor.forClass(IssueThumbnailUploadCommand.class);
        then(useCase).should().issue(command.capture());
        assertThat(command.getValue()).isEqualTo(new IssueThumbnailUploadCommand(SELLER_ID, "image/png"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"contentType\":\"image/gif\"}", "{\"contentType\":\"IMAGE/PNG\"}",
            "{\"contentType\":\"image/png; x=1\"}", "{\"contentType\":\"\"}", "{}"})
    @DisplayName("허용 형식 밖이면 400 COMMON-001, 유스케이스 미호출")
    void rejectsContentType(String body) throws Exception {
        LiveSellerControllerTest.expectFailure(
                mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)), "COMMON-001");
        then(useCase).should(never()).issue(any());
    }
}
