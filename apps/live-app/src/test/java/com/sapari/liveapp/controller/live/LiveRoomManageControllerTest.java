package com.sapari.liveapp.controller.live;

import static com.sapari.liveapp.controller.live.LiveSellerControllerTest.expectFailure;
import static com.sapari.liveapp.controller.live.LiveSellerControllerTest.expectSuccessEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.page.CursorPage;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.web.security.CurrentUserIdArgumentResolver;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.CancelLiveCommand;
import com.sapari.live.command.GetSellerRoomsCommand;
import com.sapari.live.command.UpdateLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.port.CancelLiveUseCase;
import com.sapari.live.port.GetLiveRoomDetailUseCase;
import com.sapari.live.port.GetSellerRoomsUseCase;
import com.sapari.live.port.UpdateLiveUseCase;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.PublicLiveRoomDetailView;
import com.sapari.live.view.SellerLiveRoomCountsView;
import com.sapari.live.view.SellerLiveRoomDetailView;
import com.sapari.liveapp.security.LiveUserPrincipal;

/** 방 관리 엔드포인트의 응답 계약(SPR-155 규칙). 인가는 여기서 재지 않는다 — LiveSellerControllerTest 와 같다. */
@DisplayName("라이브 방 관리 엔드포인트 — 봉투·204·상태 위반 코드·노출 필드")
class LiveRoomManageControllerTest {

    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant AT = Instant.parse("2026-11-10T09:00:00Z");
    private static final String UPDATE_BODY = """
            {"title":"새 제목","description":"새 설명","thumbnailUrl":"https://cdn/t2.jpg",
             "scheduledAt":"2026-11-10T09:00:00Z"}
            """;

    private final CancelLiveUseCase cancelLiveUseCase = mock(CancelLiveUseCase.class);
    private final UpdateLiveUseCase updateLiveUseCase = mock(UpdateLiveUseCase.class);
    private final GetSellerRoomsUseCase getSellerRoomsUseCase = mock(GetSellerRoomsUseCase.class);
    private final GetLiveRoomDetailUseCase getLiveRoomDetailUseCase = mock(GetLiveRoomDetailUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-08T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(new LiveRoomManageController(
                        cancelLiveUseCase, updateLiveUseCase, getSellerRoomsUseCase, getLiveRoomDetailUseCase))
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
    @DisplayName("취소 — 204 본문 없음, sellerId 는 인증 주체에서")
    void cancelReturns204() throws Exception {
        mvc.perform(post("/api/v1/lives/rooms/{roomId}/cancel", ROOM_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        then(cancelLiveUseCase).should().cancel(new CancelLiveCommand(ROOM_ID, SELLER_ID));
    }

    @Test
    @DisplayName("취소 불가 상태 — 400 LIVE-013, 고정 문구, roomId 미노출")
    void cancelStateViolationIs400Live013() throws Exception {
        willThrow(new InvalidLiveStateException(LiveErrorCode.CANCEL_NOT_ALLOWED, ROOM_ID.toString()))
                .given(cancelLiveUseCase).cancel(any());

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/cancel", ROOM_ID)), "LIVE-013")
                .andExpect(jsonPath("$.error.message").value("취소할 수 없는 방송 상태입니다."))
                .andExpect(content().string(not(containsString(ROOM_ID.toString()))));
    }

    @Test
    @DisplayName("수정 — 204, 본문 필드가 그대로 커맨드로")
    void updateReturns204() throws Exception {
        mvc.perform(put("/api/v1/lives/rooms/{roomId}", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        then(updateLiveUseCase).should().update(new UpdateLiveCommand(
                ROOM_ID, SELLER_ID, "새 제목", "새 설명", "https://cdn/t2.jpg", AT));
    }

    @Test
    @DisplayName("수정 불가 상태 — 400 LIVE-014")
    void updateStateViolationIs400Live014() throws Exception {
        willThrow(new InvalidLiveStateException(LiveErrorCode.UPDATE_NOT_ALLOWED, ROOM_ID.toString()))
                .given(updateLiveUseCase).update(any());

        expectFailure(mvc.perform(put("/api/v1/lives/rooms/{roomId}", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY)), "LIVE-014")
                .andExpect(jsonPath("$.error.message").value("수정할 수 없는 방송 상태입니다."))
                .andExpect(content().string(not(containsString(ROOM_ID.toString()))));
    }

    @Test
    @DisplayName("수정 — 제목 공백은 400, 유스케이스를 부르지 않는다")
    void updateRejectsBlankTitle() throws Exception {
        mvc.perform(put("/api/v1/lives/rooms/{roomId}", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY.replace("\"새 제목\"", "\" \"")))
                .andExpect(status().isBadRequest());
        then(updateLiveUseCase).should(never()).update(any());
    }

    @Test
    @DisplayName("공개 상세 — 봉투, rtmpUrl·streamKey·streamType·roomToken 키가 없다")
    void publicDetailHidesSellerFields() throws Exception {
        given(getLiveRoomDetailUseCase.getPublicDetail(ROOM_ID)).willReturn(new PublicLiveRoomDetailView(
                "제목", "설명", "https://cdn/t.jpg", "닉", LiveRoomTab.ENDED, AT, AT, AT.plusSeconds(60), "READY"));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/rooms/{roomId}/detail", ROOM_ID))
                .andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.vodStatus").value("READY"))
                .andExpect(jsonPath("$.data", not(hasKey("rtmpUrl"))))
                .andExpect(jsonPath("$.data", not(hasKey("streamKey"))))
                .andExpect(jsonPath("$.data", not(hasKey("streamType"))))
                .andExpect(jsonPath("$.data", not(hasKey("roomToken"))))
                .andExpect(jsonPath("$.data", not(hasKey("roomId"))));
    }

    @Test
    @DisplayName("판매자 상세 — rtmpUrl 은 있고 streamKey 는 없다")
    void sellerDetailHasRtmpUrlButNoKey() throws Exception {
        given(getLiveRoomDetailUseCase.getSellerDetail(any())).willReturn(new SellerLiveRoomDetailView(
                "제목", "설명", "https://cdn/t.jpg", "닉", LiveRoomTab.SCHEDULED, AT, null, null, "NONE",
                "rtmp://ingress/live"));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/seller/rooms/{roomId}", ROOM_ID))
                .andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.rtmpUrl").value("rtmp://ingress/live"))
                .andExpect(jsonPath("$.data", not(hasKey("streamKey"))));
    }

    @Test
    @DisplayName("내 방 목록 — 쿼리 파라미터가 커맨드로, CursorPage 를 봉투에")
    void sellerRoomsPassesQuery() throws Exception {
        given(getSellerRoomsUseCase.getRooms(any())).willReturn(new CursorPage<>(List.of(), null, false));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/seller/rooms")
                        .param("tab", "CANCELLED").param("cursor", "abc").param("size", "5"))
                .andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.content").isArray());
        ArgumentCaptor<GetSellerRoomsCommand> command = ArgumentCaptor.forClass(GetSellerRoomsCommand.class);
        then(getSellerRoomsUseCase).should().getRooms(command.capture());
        assertThat(command.getValue())
                .isEqualTo(new GetSellerRoomsCommand(SELLER_ID, LiveRoomTab.CANCELLED, "abc", 5));
    }

    @Test
    @DisplayName("탭별 개수 — 한 번의 호출로 네 탭")
    void sellerRoomCounts() throws Exception {
        given(getSellerRoomsUseCase.getCounts(SELLER_ID)).willReturn(new SellerLiveRoomCountsView(3, 1, 7, 2));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/seller/rooms/counts")).andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.scheduled").value(3))
                .andExpect(jsonPath("$.data.live").value(1))
                .andExpect(jsonPath("$.data.ended").value(7))
                .andExpect(jsonPath("$.data.cancelled").value(2));
    }
}
