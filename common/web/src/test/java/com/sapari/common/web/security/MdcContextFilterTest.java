package com.sapari.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.sapari.global.logging.LogFields;

import jakarta.servlet.FilterChain;

/**
 * 클라가 준 {@code X-Request-Id} 를 <b>어디까지 믿는가</b>를 고정한다.
 *
 * <p>이 값은 요청자가 고르고, 받아들이면 그 요청의 모든 로그 줄(MDC)·응답 헤더·에러 응답 본문
 * 세 곳으로 퍼진다. 검증이 없으면 유일한 경계가 서블릿 컨테이너의 헤더 상한(톰캣 기본 8KB)이라
 * 한 요청이 자기 로그 줄마다 8KB 를 더 싣게 만들 수 있다.
 *
 * <p>거부가 아니라 <b>대체</b>인 것이 핵심이다 — 400 으로 돌려주면 이상한 프록시 하나가 전 요청을
 * 깨뜨릴 수 있고, 이 값은 상관 식별자일 뿐이라 서버가 지어도 기능이 상하지 않는다. 헤더가 아예
 * 없을 때와 같은 처리라 호출자가 보는 계약도 달라지지 않는다.
 */
@DisplayName("MdcContextFilter — 클라가 준 requestId 를 어디까지 믿는가")
class MdcContextFilterTest {

    private final MdcContextFilter filter = new MdcContextFilter();

    /** 체인 안에서 본 MDC 값. 필터가 {@code finally} 에서 지우므로 밖에서는 읽을 수 없다. */
    private String requestIdSeenInsideChain;

    private MockHttpServletResponse run(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/any");
        if (headerValue != null) {
            request.addHeader("X-Request-Id", headerValue);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> requestIdSeenInsideChain = MDC.get(LogFields.REQUEST_ID);
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("형식에 맞는 값은 그대로 쓰인다 — 클라가 만든 상관 식별자를 꺾지 않는다")
    void aWellFormedValueIsKept() throws Exception {
        String given = UUID.randomUUID().toString();

        MockHttpServletResponse response = run(given);

        assertThat(requestIdSeenInsideChain).isEqualTo(given);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(given);
    }

    @Test
    @DisplayName("⭐ 상한을 넘는 값은 서버 생성값으로 대체된다 — 안 그러면 로그 줄마다 그만큼이 붙는다")
    void anOverlongValueIsReplaced() throws Exception {
        String given = "a".repeat(8 * 1024);

        MockHttpServletResponse response = run(given);

        assertThat(requestIdSeenInsideChain).isNotEqualTo(given).hasSize(36);
        // 반사도 함께 막힌다 — 응답 헤더로 되돌려 주는 자리가 같은 값을 쓴다
        assertThat(response.getHeader("X-Request-Id")).isNotEqualTo(given);
    }

    @Test
    @DisplayName("⭐ 식별자처럼 보이지 않는 값은 대체된다 — 이 값은 로그 필드 하나에 담긴다")
    void aValueOutsideTheAcceptedCharsetIsReplaced() throws Exception {
        // 인코더가 이스케이프하므로 로그 위조는 되지 않는다(실측: 한 줄·한 필드 안에 갇힌다).
        // 그래도 받아들이지 않는 이유는 사람이 읽는 자리에서 식별자가 식별자처럼 보여야 하기 때문이다.
        String given = "aaa\"},\"log.level\":\"FAKE\"";

        run(given);

        assertThat(requestIdSeenInsideChain).isNotEqualTo(given).hasSize(36);
    }

    @Test
    @DisplayName("헤더가 없으면 서버가 짓는다 — 상관 식별자 없는 줄을 남기지 않는다")
    void anAbsentHeaderIsGenerated() throws Exception {
        MockHttpServletResponse response = run(null);

        assertThat(requestIdSeenInsideChain).hasSize(36);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestIdSeenInsideChain);
    }

    @Test
    @DisplayName("요청이 끝나면 MDC 가 비워진다 — 스레드 풀 재사용으로 다음 요청에 새지 않는다")
    void theContextIsClearedAfterTheRequest() throws Exception {
        run(UUID.randomUUID().toString());

        assertThat(MDC.get(LogFields.REQUEST_ID)).isNull();
    }
}
