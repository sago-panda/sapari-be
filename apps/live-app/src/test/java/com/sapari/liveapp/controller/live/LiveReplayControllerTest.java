package com.sapari.liveapp.controller.live;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.domain.exception.LiveReplayNotFoundException;
import com.sapari.live.port.CreateLiveUseCase;
import com.sapari.live.port.EndLiveUseCase;
import com.sapari.live.port.EnterLiveUseCase;
import com.sapari.live.port.GetLiveReplayUseCase;
import com.sapari.live.port.GetLiveUseCase;
import com.sapari.live.port.PrepareIngressUseCase;
import com.sapari.live.port.StartLiveUseCase;
import com.sapari.live.view.ReplayView;

class LiveReplayControllerTest {
    private final GetLiveReplayUseCase replay = mock(GetLiveReplayUseCase.class);
    private final UUID roomId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-09-11T00:00:00Z"));
        LiveController controller = new LiveController(mock(CreateLiveUseCase.class), mock(StartLiveUseCase.class),
                mock(EnterLiveUseCase.class), mock(EndLiveUseCase.class), mock(GetLiveUseCase.class), replay,
                mock(PrepareIngressUseCase.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider)).build();
    }

    @Test
    void returnsReplayEnvelopeWithoutPrincipal() throws Exception {
        given(replay.getReplay(roomId)).willReturn(new ReplayView(roomId, "https://cdn/720p/playlist.m3u8"));
        mvc.perform(get("/api/v1/lives/rooms/{roomId}/replay", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value(roomId.toString()))
                .andExpect(jsonPath("$.data.hlsArchiveUrl").value("https://cdn/720p/playlist.m3u8"));
    }

    @Test
    void unavailableReplayReturns404Envelope() throws Exception {
        given(replay.getReplay(roomId)).willThrow(new LiveReplayNotFoundException(roomId.toString()));
        mvc.perform(get("/api/v1/lives/rooms/{roomId}/replay", roomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("LIVE-007"));
    }
}
