package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 부팅 중 <b>어느 시점부터 이 프로세스가 트래픽을 받는가</b>를 고정한다.
 *
 * <p>이것이 프레임워크 사실이고 우리 코드가 아닌데도 테스트로 두는 이유: chat 의 Mongo 인덱스 생성이
 * "포트가 열리기 전에 끝난다"는 계약은 <b>전적으로 이 순서에 기대고 있다</b>. 여기가 조용히 바뀌면
 * 그 계약은 테스트 하나 깨지지 않고 사라진다 — 인덱스는 여전히 서고, 다만 그 사이에 들어온 요청이
 * 인덱스 없는 컬렉션을 풀스캔한다.
 *
 * <p>실측(이 테스트가 재는 값): 리액티브 웹서버는 {@code SmartLifecycle}이고 phase 가
 * 2,147,481,599({@code MAX_VALUE - 2048})라 {@code finishRefresh()}에서 뜬다. 그래서
 * <b>싱글톤 사전 초기화와 가장 이른 lifecycle 은 포트 앞, {@code ApplicationRunner} 는 뒤</b>다.
 * 러너에서 무엇을 하든 그동안 LB 는 이 Pod 로 트래픽을 보낼 수 있다.
 *
 * <p>고정 포트로 TCP 를 직접 찌른다 — 웹서버 <b>객체</b>는 {@code onRefresh()}에서 이미 만들어져
 * 있어서 그 존재로는 아무것도 가를 수 없다. bind 는 {@code start()}에서 일어난다(실측: 객체가 있는
 * 시점에도 연결은 거부됐다).
 */
@DisplayName("부팅 순서 — 싱글톤은 포트 앞, 러너는 포트 뒤")
class EagerSingletonsRunBeforeThePortBindsTest {

    private static int port;
    private static final AtomicReference<String> eagerSingletonSawPort = new AtomicReference<>();
    private static final AtomicReference<String> earliestLifecycleSawPort = new AtomicReference<>();
    private static final AtomicReference<String> applicationRunnerSawPort = new AtomicReference<>();

    private static final String ACCEPTED = "ACCEPTED";
    private static final String REFUSED = "REFUSED";

    /** 상한을 둔다 — 열린 적 없는 포트는 즉시 거부되지만, 방화벽류가 끼면 무한 대기가 된다. */
    private static final int CONNECT_CEILING_MILLIS = 1500;

    private static String probeTcp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_CEILING_MILLIS);
            return ACCEPTED;
        } catch (Exception e) {
            return REFUSED;
        }
    }

    /**
     * DataSource 자동설정만 끈다 — 이 테스트는 관계형 DB를 쓰지 않고, 켜 두면 URL 이 없어 빈 생성이 실패한다.
     * 운영 앱도 같은 이유로 같은 것을 끈다({@code StreamingAppApplication}).
     */
    @Configuration
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    static class BootOrderConfig {

        /** 싱글톤 사전 초기화({@code finishBeanFactoryInitialization}) — 인덱스 생성을 옮기려는 자리. */
        @Bean
        String eagerSingleton() {
            eagerSingletonSawPort.set(probeTcp());
            return "probe";
        }

        /** phase {@code MIN_VALUE} — 모든 lifecycle 중 가장 먼저 start 된다(웹서버보다 앞). */
        @Bean
        SmartLifecycle earliestLifecycle() {
            return new SmartLifecycle() {
                @Override
                public void start() {
                    earliestLifecycleSawPort.set(probeTcp());
                }

                @Override
                public void stop() {
                    // 이 프로브는 멈출 것이 없다
                }

                @Override
                public boolean isRunning() {
                    return false;
                }

                @Override
                public int getPhase() {
                    return Integer.MIN_VALUE;
                }
            };
        }

        /** 인덱스 생성이 <b>지금까지 있던</b> 자리. */
        @Bean
        ApplicationRunner applicationRunner() {
            return args -> applicationRunnerSawPort.set(probeTcp());
        }
    }

    @Test
    @DisplayName("⭐ 러너에서는 포트가 이미 열려 있다 — 싱글톤·최초 lifecycle 에서는 닫혀 있다")
    void theRunnerIsAlreadyServingTraffic() throws Exception {
        // given: 아무도 듣지 않는 포트를 하나 확보한다(확보 즉시 닫아 웹서버에 넘긴다)
        try (ServerSocket scout = new ServerSocket(0)) {
            port = scout.getLocalPort();
        }

        // when
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BootOrderConfig.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=" + port)
                .run()) {

            // then
            assertThat(eagerSingletonSawPort.get()).as("싱글톤 사전 초기화").isEqualTo(REFUSED);
            assertThat(earliestLifecycleSawPort.get()).as("가장 이른 lifecycle").isEqualTo(REFUSED);
            assertThat(applicationRunnerSawPort.get()).as("ApplicationRunner").isEqualTo(ACCEPTED);
        }
    }
}
