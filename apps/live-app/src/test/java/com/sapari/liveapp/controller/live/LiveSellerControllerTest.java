package com.sapari.liveapp.controller.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.common.web.security.CurrentUserIdArgumentResolver;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveProductException;
import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.port.CreateLiveUseCase;
import com.sapari.live.port.EndLiveUseCase;
import com.sapari.live.port.EnterLiveUseCase;
import com.sapari.live.port.GetLiveReplayUseCase;
import com.sapari.live.port.GetLiveUseCase;
import com.sapari.live.port.GetUpcomingLivesUseCase;
import com.sapari.live.port.PrepareIngressUseCase;
import com.sapari.live.port.StartLiveUseCase;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.IngressCredentialView;
import com.sapari.live.view.StartLiveView;
import com.sapari.liveapp.security.LiveUserPrincipal;

/**
 * 판매자 엔드포인트의 응답 계약 — 성공은 봉투(CONV-12), 상태 코드는 그대로.
 *
 * <p>인가 규칙(SELLER 전용)은 여기서 재지 않는다. 주체를 SecurityContext 에 직접 심어
 * {@code @CurrentUserId} 가 sellerId 를 읽게만 한다.
 */
@DisplayName("라이브 판매자 엔드포인트 — 응답 봉투와 상태 코드")
class LiveSellerControllerTest {

    private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String START_BODY = """
            {"products":[{"productId":"22222222-2222-2222-2222-222222222222",
              "originalPrice":30000,"discountPrice":25000,"liveDiscountPrice":22500,"isPinned":true}]}
            """;

    private final CreateLiveUseCase createLiveUseCase = mock(CreateLiveUseCase.class);
    private final StartLiveUseCase startLiveUseCase = mock(StartLiveUseCase.class);
    private final EndLiveUseCase endLiveUseCase = mock(EndLiveUseCase.class);
    private final PrepareIngressUseCase prepareIngressUseCase = mock(PrepareIngressUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TimeProvider timeProvider = mock(TimeProvider.class);
        given(timeProvider.now()).willReturn(Instant.parse("2026-11-06T00:00:00Z"));
        LiveController controller = new LiveController(createLiveUseCase, startLiveUseCase,
                mock(EnterLiveUseCase.class), endLiveUseCase, mock(GetLiveUseCase.class),
                mock(GetLiveReplayUseCase.class), prepareIngressUseCase, mock(GetUpcomingLivesUseCase.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
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
    @DisplayName("방 생성 — 201 + 봉투, sellerId 는 인증 주체에서")
    void createRoomReturns201Envelope() throws Exception {
        given(createLiveUseCase.create(any())).willReturn(new CreateLiveView(ROOM_ID, "여름 라이브", "설명"));

        ResultActions result = mvc.perform(post("/api/v1/lives/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"여름 라이브","description":"설명","sellerNickname":"스토어",
                         "thumbnailUrl":"https://cdn.example.com/thumbnails/33333333-3333-3333-3333-333333333333/44444444-4444-4444-4444-444444444444",
                         "scheduledAt":"2026-11-07T09:00:00Z"}
                        """));

        expectSuccessEnvelope(result.andExpect(status().isCreated()))
                .andExpect(jsonPath("$.data.roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.data.title").value("여름 라이브"));
        ArgumentCaptor<CreateLiveCommand> command = ArgumentCaptor.forClass(CreateLiveCommand.class);
        then(createLiveUseCase).should().create(command.capture());
        assertThat(command.getValue().sellerId()).isEqualTo(SELLER_ID);
    }

    @Test
    @DisplayName("방송 시작 — 200 + 봉투")
    void startBroadcastReturns200Envelope() throws Exception {
        given(startLiveUseCase.start(any())).willReturn(
                new StartLiveView(ROOM_ID, "sfu-token", "https://cdn/index.m3u8", "wss://sfu"));

        ResultActions result = mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content(START_BODY));

        expectSuccessEnvelope(result.andExpect(status().isOk()))
                .andExpect(jsonPath("$.data.roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.data.sfuToken").value("sfu-token"))
                .andExpect(jsonPath("$.data.hlsUrl").value("https://cdn/index.m3u8"));
    }

    @Test
    @DisplayName("송출 키 발급 — 201 + 봉투")
    void prepareIngressReturns201Envelope() throws Exception {
        given(prepareIngressUseCase.prepare(any())).willReturn(
                new IngressCredentialView("IN_1", "rtmp://ingest", "stream-key"));

        ResultActions result = mvc.perform(post("/api/v1/lives/rooms/{roomId}/ingress", ROOM_ID));

        expectSuccessEnvelope(result.andExpect(status().isCreated()))
                .andExpect(jsonPath("$.data.ingressId").value("IN_1"))
                .andExpect(jsonPath("$.data.streamKey").value("stream-key"));
    }

    @Test
    @DisplayName("방송 종료 — 204, 본문 없음(봉투 대상 아님)")
    void endBroadcastReturns204WithoutBody() throws Exception {
        mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/end", ROOM_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        then(endLiveUseCase).should().end(any());
    }

    @Test
    @DisplayName("가격 위반 — 400 LIVE-008 (전에는 500)")
    void priceViolationIs400Live008() throws Exception {
        given(startLiveUseCase.start(any())).willThrow(new InvalidLiveProductException(
                LiveErrorCode.INVALID_LIVE_PRODUCT_PRICE, "할인가는 정가를 초과할 수 없습니다."));

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content(START_BODY)), "LIVE-008");
    }

    @Test
    @DisplayName("핀 상품 개수 — 400 LIVE-012")
    void pinnedCountIs400Live012() throws Exception {
        given(startLiveUseCase.start(any())).willThrow(new InvalidLiveProductException(
                LiveErrorCode.INVALID_PINNED_PRODUCT, "핀 상품은 정확히 1개여야 합니다. 현재 핀 상품 수: 2"));

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content(START_BODY)), "LIVE-012");
    }

    @Test
    @DisplayName("시작 불가 상태 — 400 LIVE-003, 응답에 roomId 가 실리지 않는다")
    void startStateViolationIs400Live003() throws Exception {
        given(startLiveUseCase.start(any())).willThrow(new InvalidLiveStateException(
                LiveErrorCode.INVALID_LIVE_STATE, ROOM_ID.toString()));

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content(START_BODY)), "LIVE-003")
                .andExpect(jsonPath("$.error.message").value("방송을 시작할 수 없는 상태입니다."))
                .andExpect(content().string(not(containsString(ROOM_ID.toString()))));
    }

    @Test
    @DisplayName("종료 불가 상태 — 400 LIVE-009")
    void endStateViolationIs400Live009() throws Exception {
        willThrow(new InvalidLiveStateException(LiveErrorCode.LIVE_END_NOT_ALLOWED, ROOM_ID.toString()))
                .given(endLiveUseCase).end(any());

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/end", ROOM_ID)), "LIVE-009")
                .andExpect(jsonPath("$.error.message").value("방송을 종료할 수 없는 상태입니다."));
    }

    @Test
    @DisplayName("송출 키 발급 불가 — 400 LIVE-011")
    void ingressViolationIs400Live011() throws Exception {
        given(prepareIngressUseCase.prepare(any())).willThrow(new InvalidLiveStateException(
                LiveErrorCode.INGRESS_NOT_ALLOWED, "이미 RTMP ingress 가 발급된 방입니다: " + ROOM_ID));

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/ingress", ROOM_ID)), "LIVE-011")
                .andExpect(content().string(not(containsString(ROOM_ID.toString()))));
    }

    @Test
    @DisplayName("입력과 무관한 IAE 는 여전히 500 — 서버 결함을 400 으로 숨기지 않는다")
    void unrelatedIllegalArgumentStays500() throws Exception {
        given(startLiveUseCase.start(any())).willThrow(new IllegalArgumentException("서버 불변식 위반"));

        mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON-004"));
    }

    @Test
    @DisplayName("방 생성 — 제목 101자는 400 COMMON-001(전에는 DB 에서 500), 유스케이스 미호출")
    void createRoomTitleTooLongIs400() throws Exception {
        expectFailure(mvc.perform(post("/api/v1/lives/rooms").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("\"" + "가".repeat(101) + "\"", "\"설명\""))), "COMMON-001");
        then(createLiveUseCase).should(never()).create(any());
    }

    @Test
    @DisplayName("방 생성 — description 이 없어도 201")
    void createRoomWithoutDescriptionIs201() throws Exception {
        given(createLiveUseCase.create(any())).willReturn(new CreateLiveView(ROOM_ID, "여름 라이브", null));

        mvc.perform(post("/api/v1/lives/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("\"여름 라이브\"", "null")))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateLiveCommand> command = ArgumentCaptor.forClass(CreateLiveCommand.class);
        then(createLiveUseCase).should().create(command.capture());
        assertThat(command.getValue().description()).isNull();
    }

    @Test
    @DisplayName("방 생성 — 예정 시각 LIVE-015 / 썸네일 LIVE-016 은 400")
    void createRoomInputViolations() throws Exception {
        given(createLiveUseCase.create(any()))
                .willThrow(new InvalidLiveRoomException(LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE, "x"))
                .willThrow(new InvalidLiveRoomException(LiveErrorCode.INVALID_THUMBNAIL_URL, "x"));

        expectFailure(mvc.perform(post("/api/v1/lives/rooms").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("\"제목\"", "null"))), "LIVE-015")
                .andExpect(jsonPath("$.error.message").value("방송 예정 시각은 현재 이후여야 합니다."));
        expectFailure(mvc.perform(post("/api/v1/lives/rooms").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("\"제목\"", "null"))), "LIVE-016")
                .andExpect(jsonPath("$.error.message").value("허용되지 않은 썸네일 주소입니다."));
    }

    @Test
    @DisplayName("방송 시작 — 상품 51개는 400 COMMON-001, 유스케이스 미호출(방 조회보다 먼저)")
    void startWith51ProductsIs400() throws Exception {
        String products = IntStream.range(0, 51)
                .mapToObj(i -> product(UUID.randomUUID(), i == 0))
                .collect(Collectors.joining(","));

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"products\":[" + products + "]}")), "COMMON-001");
        then(startLiveUseCase).should(never()).start(any());
    }

    @Test
    @DisplayName("방송 시작 — productId 중복은 400 COMMON-001, 유스케이스 미호출")
    void startWithDuplicateProductIdIs400() throws Exception {
        UUID same = UUID.randomUUID();
        String body = "{\"products\":[" + product(same, true) + "," + product(same, false) + "]}";

        expectFailure(mvc.perform(post("/api/v1/lives/rooms/{roomId}/broadcast/start", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body)), "COMMON-001")
                .andExpect(jsonPath("$.error.message").value(containsString("상품 ID가 중복되었습니다.")));
        then(startLiveUseCase).should(never()).start(any());
    }

    private static String createBody(String titleJson, String descriptionJson) {
        return "{\"title\":" + titleJson + ",\"description\":" + descriptionJson + ",\"sellerNickname\":\"스토어\","
                + "\"thumbnailUrl\":\"https://cdn.example.com/thumbnails/a/b\",\"scheduledAt\":\"2026-11-07T09:00:00Z\"}";
    }

    private static String product(UUID productId, boolean pinned) {
        return "{\"productId\":\"" + productId + "\",\"originalPrice\":30000,\"discountPrice\":25000,"
                + "\"liveDiscountPrice\":22500,\"isPinned\":" + pinned + "}";
    }

    /** 실패 봉투: 400, success=false, data 는 null 로 존재, error.code. */
    static ResultActions expectFailure(ResultActions result, String code) throws Exception {
        return result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$", hasKey("data")))
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    /** 성공 봉투: success=true, error 는 키가 있고 값이 null(생략되지 않음). */
    static ResultActions expectSuccessEnvelope(ResultActions result) throws Exception {
        return result
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$", hasKey("error")))
                .andExpect(jsonPath("$.error", nullValue()));
    }
}
