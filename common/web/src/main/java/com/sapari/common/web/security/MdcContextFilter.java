package com.sapari.common.web.security;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.sapari.global.logging.LogFields;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 단위 로깅 컨텍스트를 MDC에 주입한다.
 * - requestId: 한 요청의 모든 로그 라인을 상관(correlation)하기 위한 식별자
 * - userId: 인증 이후 {@code JwtAuthenticationFilter}가 채운다
 *
 * 결정된 requestId는 응답 헤더(X-Request-Id)로도 노출한다.
 * 시큐리티 필터 체인보다 먼저 실행되어 전체 요청을 감싸고, finally에서 MDC를 정리한다.
 */
public class MdcContextFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * 클라가 준 requestId 를 받아들이는 상한.
     *
     * <p><b>왜 필요한가.</b> 이 값은 요청자가 고르고, 받아들이면 세 곳으로 퍼진다 — 그 요청이
     * 남기는 <b>모든</b> 로그 줄의 MDC, 응답 헤더, 그리고 에러 응답 본문의
     * {@code ErrorResponse.requestId}. 검증이 없으면 유일한 경계가 서블릿 컨테이너의 헤더 상한
     * (톰캣 기본 8KB)이라, 한 요청이 자기 로그 줄마다 8KB 를 더 싣게 만들 수 있다.
     *
     * <p><b>적용 범위가 최근에 넓어졌다.</b> 전에는 평문 패턴에 {@code %X{requestId}} 를 가진 앱이
     * 하나뿐이어서 나머지는 MDC 가 출력에 실리지 않았다. 지금은 다섯 앱이 모두 ECS 로 MDC 를
     * 최상위 필드로 싣는다 — 같은 입력의 비용이 그만큼 커졌다.
     *
     * <p><b>로그 위조는 아니다</b>(실측): 인용부호와 개행을 섞어 넣어도 ECS 인코더가 전부
     * 이스케이프해 값이 <b>한 줄·한 필드</b> 안에 갇힌다. 그래서 이 상한이 막는 것은 위조가 아니라
     * <b>증폭과 반사</b>다.
     *
     * <p>64 는 흔히 쓰는 상관 식별자 형식을 모두 덮는다 — UUID 36자, W3C {@code traceparent} 55자,
     * nginx {@code $request_id} 32자. 어긋나는 값은 거부가 아니라 <b>서버 생성값으로 대체</b>한다:
     * 헤더가 아예 없을 때와 같은 처리라 호출자가 보는 계약이 달라지지 않는다.
     */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    /**
     * 받아들이는 문자 집합.
     *
     * <p>로그 필드 하나에 담길 값이라 영숫자와 구분자({@code . _ -})만 둔다. 공백·제어문자·인용부호를
     * 빼는 것은 인코더를 믿지 못해서가 아니라(위에 적은 대로 이스케이프된다) <b>사람이 읽는 자리에서
     * 식별자가 식별자처럼 보여야</b> 하기 때문이다.
     */
    private static final Pattern ACCEPTED_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(LogFields.REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId); // 클라가 장애 신고 시 로그 추적에 쓰도록 응답에 노출

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드 풀 재사용 시 다음 요청으로의 컨텍스트 누수 방지 (requestId + userId 모두 정리)
            MDC.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        if (header == null || header.isBlank()
                || header.length() > MAX_REQUEST_ID_LENGTH
                || !ACCEPTED_REQUEST_ID.matcher(header).matches()) {
            return UUID.randomUUID().toString();
        }
        return header;
    }
}
