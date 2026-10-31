package com.sapari.liveapp.controller.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sapari.chat.command.GetChatHistoryCommand;
import com.sapari.chat.port.GetChatHistoryUseCase;
import com.sapari.chat.view.GetChatHistoryView;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.config.LiveSecurityConfig;
import com.sapari.liveapp.security.LiveUserPrincipal;

import org.mockito.ArgumentCaptor;

/**
 * 이력 엔드포인트의 <b>관문과 신원 전달</b>을 고정한다.
 *
 * <p>두 가지가 걸려 있다.
 *
 * <p>① <b>비인증도 지나야 한다</b> — 다시보기 채팅은 비회원도 본다(설계 확정). 이 엔드포인트가
 * {@code /api/v1/lives/**} GET 공개 규칙에 든다는 사실이 그걸 만드는데, 그 규칙은 주석으로만 적혀
 * 있으면 다음 사람이 규칙 하나를 위로 올리는 순간 조용히 깨진다 — 그때 401 이 되고, 비회원 다시보기가
 * 사라진 것을 아무도 모른 채 배포된다.
 *
 * <p>② <b>신원은 인증 주체에서만 온다</b> — 쿼리로 받으면 아무나 방 주인 id 를 넣어 남의 방 구매자
 * 이메일과 욕설 원문을 읽는다. 그래서 컨트롤러가 principal 에서 채우는지를 명령으로 확인한다.
 */
@WebMvcTest(controllers = ChatHistoryController.class,
        properties = "management.server.port=9999")
// GlobalExceptionHandler 를 명시로 올린다 — 이 슬라이스에는 컴포넌트 스캔이 없어서
// @RestControllerAdvice 가 자동으로 잡히지 않는다. 없으면 도메인 예외가 매핑되지 않고
// ServletException 으로 터져 "400 이 아니다" 가 아니라 "테스트가 못 돈다" 가 된다.
@Import({ChatHistoryController.class, LiveSecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("이력 엔드포인트 — 비회원도 지나고, 신원은 인증 주체에서만 온다")
class ChatHistoryControllerTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REQUESTER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetChatHistoryUseCase getChatHistoryUseCase;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 에러 응답의 타임스탬프에 쓰인다.
     *
     * <p>중첩 {@code @TestConfiguration} 으로 고정 시계를 세우지 않는다 — 그 중첩 클래스가
     * {@code com.sapari} 아래라 <b>전체 컨텍스트를 띄우는 다른 테스트의 컴포넌트 스캔에 함께 잡히고</b>,
     * 같은 모양이 이미 하나 있어서 {@code timeProvider} 빈 정의가 겹쳐 그 테스트가 통째로 못 뜬다
     * (실측: {@code BeanDefinitionOverrideException}). 목으로 두면 이 슬라이스 안에만 머문다.
     */
    @MockitoBean
    private TimeProvider timeProvider;

    private static RequestPostProcessor as(String role) {
        LiveUserPrincipal principal = new LiveUserPrincipal(REQUESTER_ID, role, "닉", "a@b.c");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return authentication(auth);
    }

    private void stubEmpty() {
        given(getChatHistoryUseCase.getHistory(any())).willReturn(new GetChatHistoryView(List.of()));
    }

    @Test
    @DisplayName("⭐ 비인증도 지난다 — 공개 GET 규칙이 뒤로 밀리면 401 이 되고 비회원 다시보기가 사라진다")
    void anonymousPassesTheGate() throws Exception {
        // given
        stubEmpty();

        // when & then
        mockMvc.perform(get("/api/v1/lives/{roomId}/chat/history", ROOM_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("⭐ 비인증 요청은 명령에 신원이 비어 간다 — 특권 판정은 서비스가 하고 컨트롤러는 좁히지 않는다")
    void anonymousCarriesNoIdentity() throws Exception {
        // given
        stubEmpty();

        // when
        mockMvc.perform(get("/api/v1/lives/{roomId}/chat/history", ROOM_ID)).andExpect(status().isOk());

        // then
        assertThat(captured().requesterId()).isNull();
        assertThat(captured().requesterRole()).isNull();
    }

    @Test
    @DisplayName("⭐ 인증 요청은 principal 의 id·역할이 그대로 간다 — 쿼리로 받으면 남의 방 이메일이 열린다")
    void anIdentifiedRequesterCarriesThePrincipal() throws Exception {
        // given
        stubEmpty();

        // when
        mockMvc.perform(get("/api/v1/lives/{roomId}/chat/history", ROOM_ID).with(as("SELLER")))
                .andExpect(status().isOk());

        // then
        assertThat(captured().requesterId()).isEqualTo(REQUESTER_ID);
        assertThat(captured().requesterRole()).isEqualTo("SELLER");
    }

    @Test
    @DisplayName("size 를 안 보내면 기본값이 쓰인다 — 스크롤 첫 페이지가 파라미터 없이 와도 된다")
    void sizeHasADefault() throws Exception {
        // given
        stubEmpty();

        // when
        mockMvc.perform(get("/api/v1/lives/{roomId}/chat/history", ROOM_ID)).andExpect(status().isOk());

        // then
        assertThat(captured().size()).isEqualTo(15);
    }

    @Test
    @DisplayName("두 모드를 함께 보내면 400 이다 — 한쪽을 조용히 무시하지 않는다")
    void bothModesTogetherAreRejected() throws Exception {
        // when & then: 명령의 생성자가 거부하고 공통 처리기가 400 으로 바꾼다
        mockMvc.perform(get("/api/v1/lives/{roomId}/chat/history", ROOM_ID)
                        .param("beforeId", "68000000000000000000000a")
                        .param("playbackPositionMs", "1000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VOD 위치만 보내면 그대로 전달된다")
    void thePlaybackPositionIsForwarded() throws Exception {
        // given
        stubEmpty();

        // when
        mockMvc.perform(get("/api/v1/lives/{roomId}/chat/history", ROOM_ID)
                        .param("playbackPositionMs", "600000"))
                .andExpect(status().isOk());

        // then
        assertThat(captured().playbackPositionMs()).isEqualTo(600_000L);
        assertThat(captured().beforeId()).isNull();
    }

    private GetChatHistoryCommand captured() {
        ArgumentCaptor<GetChatHistoryCommand> captor = ArgumentCaptor.captor();
        then(getChatHistoryUseCase).should().getHistory(captor.capture());
        return captor.getValue();
    }

    /** {@code @WebMvcTest} 가 앱 클래스까지 거슬러 올라가 JPA 를 켜는 것을 막는다. */
    @SpringBootConfiguration
    static class TestApplication {
    }
}
