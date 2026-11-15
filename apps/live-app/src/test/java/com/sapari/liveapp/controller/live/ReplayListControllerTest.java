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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.GetReplaysQuery;
import com.sapari.live.port.GetReplaysUseCase;
import com.sapari.live.view.ReplaySummaryView;

/** 다시보기 공개 목록 응답 계약. 공개 여부는 {@code LivePublicRoutesTest} 가 본다. */
@DisplayName("다시보기 목록 엔드포인트 — 봉투·노출 필드·커서 400")
class ReplayListControllerTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final GetReplaysUseCase useCase = mock(GetReplaysUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-12T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(new ReplayListController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider))
                .build();
    }

    @Test
    @DisplayName("200 — 인증 없이, 목록 필드만 있고 재생 주소는 없다")
    void returnsSummaryWithoutPlaybackUrl() throws Exception {
        given(useCase.getReplays(any())).willReturn(new CursorPage<>(List.of(new ReplaySummaryView(
                ROOM_ID, SELLER_ID, "판매자", "지난 방송", "https://cdn/t.jpg", 3600L,
                Instant.parse("2026-11-11T11:05:12.654321Z"))), "next", true));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/replays")).andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.content[0].roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].sellerId").value(SELLER_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.data.content[0].title").value("지난 방송"))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://cdn/t.jpg"))
                .andExpect(jsonPath("$.data.content[0].durationSeconds").value(3600))
                .andExpect(jsonPath("$.data.content[0].endedAt").exists())
                .andExpect(jsonPath("$.data.content[0]", not(hasKey("hlsArchiveUrl"))))
                .andExpect(jsonPath("$.data.nextCursor").value("next"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("cursor·size 가 질의로 전달된다")
    void passesQuery() throws Exception {
        given(useCase.getReplays(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mvc.perform(get("/api/v1/lives/replays").param("cursor", "abc").param("size", "5"))
                .andExpect(status().isOk());

        then(useCase).should().getReplays(new GetReplaysQuery("abc", 5));
    }

    @Test
    @DisplayName("깨진 커서 — 400 봉투")
    void invalidCursorIs400() throws Exception {
        given(useCase.getReplays(any())).willThrow(new InvalidCursorException("bad"));

        mvc.perform(get("/api/v1/lives/replays").param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").exists());
    }
}
