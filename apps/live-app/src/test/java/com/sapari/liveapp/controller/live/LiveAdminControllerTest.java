package com.sapari.liveapp.controller.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.page.OffsetPage;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.web.security.CurrentUserIdArgumentResolver;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.AdminEndLiveCommand;
import com.sapari.live.command.ChangeSellerLivePermissionCommand;
import com.sapari.live.command.GetAdminRoomsQuery;
import com.sapari.live.domain.exception.InvalidLiveQueryException;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.port.AdminEndLiveUseCase;
import com.sapari.live.port.ChangeSellerLivePermissionUseCase;
import com.sapari.live.port.GetAdminRoomsUseCase;
import com.sapari.live.view.AdminLiveRoomSummary;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 관리자 엔드포인트(종료·방 목록·판매자 라이브 권한)의 입력·응답 계약. 인가(ADMIN 전용)는 {@code LiveAdminAuthorizationTest} 가 본다.
 */
@DisplayName("관리자 엔드포인트 — 종료 사유·방 목록 파라미터·권한 변경 검증과 상태 코드")
class LiveAdminControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final AdminEndLiveUseCase adminEndLiveUseCase = mock(AdminEndLiveUseCase.class);
    private final GetAdminRoomsUseCase getAdminRoomsUseCase = mock(GetAdminRoomsUseCase.class);
    private final ChangeSellerLivePermissionUseCase changeSellerLivePermissionUseCase =
            mock(ChangeSellerLivePermissionUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-20T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(new LiveAdminController(
                        adminEndLiveUseCase, getAdminRoomsUseCase, changeSellerLivePermissionUseCase))
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider))
                .build();

        LiveUserPrincipal principal = new LiveUserPrincipal(
                ADMIN_ID, UUID.randomUUID(), UUID.randomUUID(), "ADMIN", "닉", "a@b.c");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ResultActions forceEnd(String body) throws Exception {
        return mvc.perform(post("/api/v1/lives/admin/rooms/{roomId}/force-end", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String reason(String value) {
        return "{\"reason\":\"" + value + "\"}";
    }

    @Test
    @DisplayName("204, 본문 없음 — 관리자 id 는 인증 주체에서, 사유는 앞뒤 공백을 잘라 넘긴다")
    void forceEnd_passesAdminAndStrippedReason() throws Exception {
        forceEnd(reason("  정책 위반  "))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<AdminEndLiveCommand> command = ArgumentCaptor.forClass(AdminEndLiveCommand.class);
        then(adminEndLiveUseCase).should().end(command.capture());
        assertThat(command.getValue()).isEqualTo(new AdminEndLiveCommand(ROOM_ID, ADMIN_ID, "정책 위반"));
    }

    @Test
    @DisplayName("사유 없음·공백뿐·501자 — 400 COMMON-001, 유스케이스 미호출")
    void forceEnd_invalidReasonIs400() throws Exception {
        for (String body : List.of("{}", reason("   "), reason("가".repeat(501)))) {
            forceEnd(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON-001"));
        }
        then(adminEndLiveUseCase).should(never()).end(any());
    }

    @Test
    @DisplayName("사유에 NUL 이 있으면 400 COMMON-001 — Postgres 가 text 에 0x00 을 받지 않아 종료까지 롤백되던 경로")
    void forceEnd_nulInReasonIs400() throws Exception {
        forceEnd(reason("a\\u0000b"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON-001"));
        then(adminEndLiveUseCase).should(never()).end(any());
    }

    @Test
    @DisplayName("사유의 줄바꿈은 허용 — 막는 것은 NUL 하나다")
    void forceEnd_newlineInReasonPasses() throws Exception {
        forceEnd(reason("1차 경고\\n2차 위반")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("사유 500자 — 경계값은 통과")
    void forceEnd_reasonAtLimitPasses() throws Exception {
        forceEnd(reason("가".repeat(500))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("종료 불가 상태 — 400 LIVE-009")
    void forceEnd_notEndableIs400Live009() throws Exception {
        willThrow(new InvalidLiveStateException(LiveErrorCode.LIVE_END_NOT_ALLOWED, ROOM_ID.toString()))
                .given(adminEndLiveUseCase).end(any());

        forceEnd(reason("사유"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LIVE-009"));
    }

    @Test
    @DisplayName("방 없음 — 404 LIVE-002")
    void forceEnd_roomNotFoundIs404() throws Exception {
        willThrow(new LiveNotFoundException(ROOM_ID.toString())).given(adminEndLiveUseCase).end(any());

        forceEnd(reason("사유"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LIVE-002"));
    }

    private ResultActions changePermission(String body) throws Exception {
        return mvc.perform(patch("/api/v1/lives/admin/sellers/{sellerId}/live-permission", SELLER_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("권한 변경 — 204, 본문 없음. 관리자 id 는 인증 주체에서, 사유는 앞뒤 공백을 잘라 넘긴다")
    void changePermission_passesAdminValueAndStrippedReason() throws Exception {
        changePermission("{\"liveEnabled\":false,\"reason\":\"  정책 위반  \"}")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<ChangeSellerLivePermissionCommand> command =
                ArgumentCaptor.forClass(ChangeSellerLivePermissionCommand.class);
        then(changeSellerLivePermissionUseCase).should().change(command.capture());
        assertThat(command.getValue())
                .isEqualTo(new ChangeSellerLivePermissionCommand(SELLER_ID, ADMIN_ID, false, "정책 위반"));
    }

    @Test
    @DisplayName("권한 변경 — liveEnabled 없음, 사유 없음·공백뿐·501자·NUL 은 400 COMMON-001, 유스케이스 미호출")
    void changePermission_invalidBodyIs400() throws Exception {
        for (String body : List.of(
                "{\"reason\":\"사유\"}",
                "{\"liveEnabled\":false}",
                "{\"liveEnabled\":false,\"reason\":\"   \"}",
                "{\"liveEnabled\":false,\"reason\":\"" + "가".repeat(501) + "\"}",
                "{\"liveEnabled\":false,\"reason\":\"a\\u0000b\"}")) {
            changePermission(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON-001"));
        }
        then(changeSellerLivePermissionUseCase).should(never()).change(any());
    }

    @Test
    @DisplayName("방 목록 — 파라미터를 그대로 넘기고, 페이지 메타를 봉투에 싣는다")
    void rooms_passesParamsAndWrapsPage() throws Exception {
        UUID sellerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Instant at = Instant.parse("2026-11-20T10:00:00Z");
        AdminLiveRoomSummary row = new AdminLiveRoomSummary(ROOM_ID, sellerId, "닉", "제목", null,
                LiveRoomTab.CANCELLED, at, at.plusSeconds(10), at.plusSeconds(100));
        given(getAdminRoomsUseCase.getRooms(any())).willReturn(OffsetPage.of(List.of(row), 1, 10, 11));

        mvc.perform(get("/api/v1/lives/admin/rooms")
                        .param("status", "CANCELLED").param("sellerId", sellerId.toString())
                        .param("from", "2026-11-01T00:00:00Z").param("to", "2026-12-01T00:00:00Z")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.content[0].startedAt").exists())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        then(getAdminRoomsUseCase).should().getRooms(new GetAdminRoomsQuery(LiveRoomTab.CANCELLED, sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), 1, 10));
    }

    @Test
    @DisplayName("방 목록 — 파라미터가 없으면 모두 null 로 넘긴다")
    void rooms_noParams() throws Exception {
        given(getAdminRoomsUseCase.getRooms(any())).willReturn(OffsetPage.of(List.of(), 0, 15, 0));

        mvc.perform(get("/api/v1/lives/admin/rooms")).andExpect(status().isOk());

        then(getAdminRoomsUseCase).should().getRooms(new GetAdminRoomsQuery(null, null, null, null, null, null));
    }

    @Test
    @DisplayName("방 목록 — 형식이 틀린 status·sellerId·기간·page 는 400, 유스케이스 미호출")
    void rooms_malformedParamsAre400() throws Exception {
        List<String[]> bad = List.of(
                new String[]{"status", "ON_AIR"},
                new String[]{"sellerId", "not-a-uuid"},
                new String[]{"from", "2026-11-01"},
                new String[]{"to", "어제"},
                new String[]{"page", "x"},
                new String[]{"size", "1.5"});
        for (String[] p : bad) {
            mvc.perform(get("/api/v1/lives/admin/rooms").param(p[0], p[1]))
                    .andExpect(status().isBadRequest());
        }
        then(getAdminRoomsUseCase).should(never()).getRooms(any());
    }

    @Test
    @DisplayName("방 목록 — 서비스가 거부한 조건(음수 page·뒤집힌 기간)은 400 LIVE-020")
    void rooms_rejectedQueryIs400Live020() throws Exception {
        willThrow(new InvalidLiveQueryException(LiveErrorCode.INVALID_ADMIN_ROOM_QUERY, "page"))
                .given(getAdminRoomsUseCase).getRooms(any());

        mvc.perform(get("/api/v1/lives/admin/rooms").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LIVE-020"));
    }
}
