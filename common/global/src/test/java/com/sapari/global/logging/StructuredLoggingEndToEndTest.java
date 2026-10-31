package com.sapari.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 실제로 앱을 띄워 <b>콘솔에 나가는 바이트</b>를 본다.
 *
 * <p>단위 테스트는 프로퍼티가 심겼는지까지만 본다. 그걸로는 못 잡는 실패가 있다 — 로깅 시스템이
 * {@code EnvironmentPostProcessor} 보다 <b>먼저</b> 초기화되면 심은 값이 쓰이지 않고, 그때 프로퍼티는
 * 멀쩡히 "ecs" 인데 출력은 평문이다. 두 리스너의 순서는 스프링의 것이고 우리가 쓴 코드가 아니라,
 * 버전이 올라가며 바뀌면 지표가 아니라 <b>로그가 통째로</b> 모양을 잃는다.
 *
 * <p>MDC 를 함께 넣는다 — ECS 를 고른 이유 자체가 "MDC 가 그대로 최상위 필드가 된다" 이고, 그게
 * 깨지면 상관 식별자를 붙이는 뒤의 작업이 전부 무의미해진다.
 */
@DisplayName("구조화 로깅 — 실제 출력이 ECS JSON 이다")
class StructuredLoggingEndToEndTest {

    /** 이름이 유도 규칙({@code ...Application})에 걸려야 service.name 까지 함께 검증된다. */
    @SpringBootApplication
    static class ProbeAppApplication {
    }

    @Test
    @DisplayName("⭐ 콘솔 출력이 ECS JSON 이고 MDC 가 최상위 필드로 실린다")
    void theConsoleEmitsEcsJsonWithMdcFields() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(ProbeAppApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            MDC.put("requestId", "REQ-1");
            MDC.put("sessionId", "s-42");
            LoggerFactory.getLogger(StructuredLoggingEndToEndTest.class)
                    .error("강퇴 조회 실패 — fail-open 으로 전송 허용 roomId={}", "ROOM-9",
                            new IllegalStateException("Redis down"));
            MDC.clear();
        } finally {
            System.setOut(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);

        // then: 평문이 아니라 JSON 이다
        assertThat(output).as("출력이 ECS JSON 이 아니다 — 로깅 시스템이 기본값보다 먼저 초기화됐을 수 있다")
                .contains("\"ecs\":{\"version\":");
        assertThat(output).contains("\"log\":{\"level\":\"ERROR\"");
        // 메시지는 그대로, 한글이 깨지지 않는다
        assertThat(output).contains("fail-open 으로 전송 허용 roomId=ROOM-9");
        // ⭐ MDC 가 최상위 필드 — ECS 를 고른 이유가 이것이다
        assertThat(output).as("MDC 가 JSON 필드로 실리지 않았다").contains("\"requestId\":\"REQ-1\"");
        assertThat(output).contains("\"sessionId\":\"s-42\"");
        // 예외는 구조화된 자리에
        assertThat(output).contains("\"error\":{\"type\":\"java.lang.IllegalStateException\"");
        assertThat(output).contains("\"stack_trace\"");
        // ⭐ 어느 앱이 쓴 줄인지 — 다섯 앱이 한 스트림에 섞인다
        assertThat(output).as("service.name 이 없으면 다섯 앱 로그를 가를 수 없다")
                .contains("\"name\":\"probe-app\"");
    }
}
