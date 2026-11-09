package com.sapari.liveapp.controller.live;

import static com.sapari.liveapp.controller.live.LiveSellerControllerTest.expectFailure;
import static com.sapari.liveapp.controller.live.LiveSellerControllerTest.expectSuccessEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.port.CreateLiveUseCase;
import com.sapari.live.port.EndLiveUseCase;
import com.sapari.live.port.EnterLiveUseCase;
import com.sapari.live.port.GetLiveReplayUseCase;
import com.sapari.live.port.GetLiveUseCase;
import com.sapari.live.port.GetUpcomingLivesUseCase;
import com.sapari.live.command.GetUpcomingLivesQuery;
import com.sapari.live.view.UpcomingLiveView;
import com.sapari.common.page.CursorPage;
import com.sapari.live.port.PrepareIngressUseCase;
import com.sapari.live.port.StartLiveUseCase;
import com.sapari.live.view.EnterLiveView;
import com.sapari.live.view.GetLiveView;

/** 공개(시청자) 엔드포인트의 응답 계약 — 게스트(주체 없음)로 호출한다. */
@DisplayName("라이브 시청자 엔드포인트 — 응답 봉투와 상태 코드")
class LiveViewerControllerTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final EnterLiveUseCase enterLiveUseCase = mock(EnterLiveUseCase.class);
    private final GetLiveUseCase getLiveUseCase = mock(GetLiveUseCase.class);
    private final GetUpcomingLivesUseCase getUpcomingLivesUseCase = mock(GetUpcomingLivesUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-06T00:00:00Z"));
        LiveController controller = new LiveController(mock(CreateLiveUseCase.class), mock(StartLiveUseCase.class),
                enterLiveUseCase, mock(EndLiveUseCase.class), getLiveUseCase,
                mock(GetLiveReplayUseCase.class), mock(PrepareIngressUseCase.class), getUpcomingLivesUseCase);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider))
                .build();
    }

    @Test
    @DisplayName("게스트 입장 — 200 + 봉투, 회원 신원 없이 커맨드가 간다")
    void guestEnterReturns200Envelope() throws Exception {
        given(enterLiveUseCase.enter(any())).willReturn(new EnterLiveView("https://cdn/index.m3u8", "room-token"));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/rooms/{roomId}", ROOM_ID))
                .andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.hlsUrl").value("https://cdn/index.m3u8"))
                .andExpect(jsonPath("$.data.roomToken").value("room-token"));
        ArgumentCaptor<EnterLiveCommand> command = ArgumentCaptor.forClass(EnterLiveCommand.class);
        then(enterLiveUseCase).should().enter(command.capture());
        assertThat(command.getValue().isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("방송 중 목록 — 200 + 봉투")
    void getRoomsReturns200Envelope() throws Exception {
        given(getLiveUseCase.getRooms(any())).willReturn(new GetLiveView(List.of()));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/rooms")).andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.rooms").isArray());
    }

    @Test
    @DisplayName("예정 방송 목록 — 200 + 봉투 안에 커서 페이지, 커서·크기가 그대로 전달된다")
    void getUpcomingReturnsCursorPageEnvelope() throws Exception {
        UpcomingLiveView row = new UpcomingLiveView(ROOM_ID, UUID.randomUUID(), "판매자", "내일 방송", null,
                Instant.parse("2026-11-07T10:00:00Z"));
        given(getUpcomingLivesUseCase.getUpcoming(any())).willReturn(new CursorPage<>(List.of(row), "next", true));

        expectSuccessEnvelope(mvc.perform(get("/api/v1/lives/upcoming").param("cursor", "abc").param("size", "5"))
                .andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.content[0].roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].scheduledAt").exists())
                .andExpect(jsonPath("$.data.nextCursor").value("next"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        then(getUpcomingLivesUseCase).should().getUpcoming(new GetUpcomingLivesQuery("abc", 5));
    }

    @Test
    @DisplayName("방송 중이 아닌 방 입장 — 400 LIVE-010 (전에는 '시작할 수 없는 상태' 문구)")
    void enterNonLiveRoomIs400Live010() throws Exception {
        given(enterLiveUseCase.enter(any())).willThrow(
                new InvalidLiveStateException(LiveErrorCode.LIVE_ENTER_NOT_ALLOWED, ROOM_ID.toString()));

        expectFailure(mvc.perform(get("/api/v1/lives/rooms/{roomId}", ROOM_ID)), "LIVE-010")
                .andExpect(jsonPath("$.error.message").value("방송 중인 방이 아닙니다."));
    }
}
