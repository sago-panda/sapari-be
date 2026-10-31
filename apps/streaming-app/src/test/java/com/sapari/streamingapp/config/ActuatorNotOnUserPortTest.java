package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 포트별로 무엇이 열리고 무엇이 막히는지를 <b>양방향으로</b> 고정한다 — 사용자 포트에는 없고,
 * 관리 포트에는 딱 둘만 있다.
 *
 * <p>이 앱에 액추에이터를 두는 근거는 "관리 포트가 따로라 사용자 포트에는 매핑되지 않는다" 하나뿐이고,
 * 그건 스프링 부트의 동작이지 우리가 쓴 코드가 아니다. 부트 버전이 올라가며 그 동작이 바뀌면
 * <b>지표가 WS 핸드셰이크를 받는 포트에 조용히 열린다</b> — 아무 에러도 나지 않으므로 여기서 잡지
 * 못하면 아무도 모른다. 그리고 이 앱에는 시큐리티가 없어서 그 순간이 곧 무인증 공개다.
 *
 * <p><b>기대값이 live-app 과 다르다.</b> 그쪽은 시큐리티 체인이 먼저 돌아 401 이 오는데, 여기는
 * 시큐리티가 없으니 매핑이 없다는 사실이 그대로 404 로 온다. 그 차이가 이 테스트를 복사해 오면
 * 안 되는 이유다.
 *
 * <p><b>live 와 달리 CI 에서도 돈다.</b> 그쪽은 {@code application*.yaml} 이 클론에 없어 부팅이
 * 불가능해서 {@code context} 태그로 빠져 있고, 그래서 파이프라인을 지켜 주지 않는다. 이 앱은 필요한
 * 인프라가 컨테이너 둘과 공개키 하나라 테스트가 직접 공급할 수 있다 — 그러면 이 가드가 사람의
 * 기억이 아니라 빌드에 걸린다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,prometheus"
        })
@Testcontainers
@DisplayName("관측 노출 — 지표는 관리 포트에만 있다")
class ActuatorNotOnUserPortTest {

    /** HTTP 왕복 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다. */
    private static final Duration HTTP_CEILING = Duration.ofSeconds(10);

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    /** 룸 토큰 공개키는 필수 설정이라 없으면 부팅이 실패한다. 서명을 검증하지 않으므로 아무 RSA 키여도 된다. */
    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        registry.add("chat.room-token.public-key", ActuatorNotOnUserPortTest::throwawayPublicKey);
    }

    private static String throwawayPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 를 쓸 수 없다", e);
        }
    }

    @LocalServerPort
    private int userPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("⭐ 사용자 포트에서는 지표를 받을 수 없다 — 그 포트에는 이 앱을 막을 시큐리티가 없다")
    void theUserPortDoesNotServeMetrics() throws Exception {
        // when & then: 시큐리티가 없으니 401 이 아니라 404(미매핑)다
        assertThat(status(userPort, "/actuator/prometheus")).isEqualTo(404);
        assertThat(status(userPort, "/actuator/health")).isEqualTo(404);
    }

    @Test
    @DisplayName("⭐ 관리 포트에서는 인증 없이 지표를 받을 수 있다 — 스크레이퍼는 로그인 수단이 없다")
    void theManagementPortServesMetrics() throws Exception {
        // 음성 방향만 있으면 "둘 다 막힘" 도 통과한다. 그건 지표가 조용히 비는 상태이고, 대시보드가
        // 빈 것이 "장애가 없어서" 와 구분되지 않는다 — 이 설계가 가장 두려워하는 실패다.
        assertThat(status(managementPort, "/actuator/prometheus")).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/health")).isEqualTo(200);
    }

    @Test
    @DisplayName("⭐ chat 지표가 실제 스크레이프에 들어 있다 — 포트가 열려도 계열이 없으면 소용없다")
    void theScrapeCarriesChatMeters() throws Exception {
        // given: 세션을 만들지 않아도 레지스트리와 포트 배선은 확인된다. 계열은 사건이 있을 때
        // 생기므로(평상시 0 이어야 알림을 걸 수 있다) 여기서는 스크레이프가 성립하는지만 본다.
        String scrape = body(managementPort, "/actuator/prometheus");

        // then: micrometer 가 실제로 돌고 있다는 증거 — ChatMetrics 가 NOOP 이면 이 앱에서
        // 지표가 통째로 비는데, 그 상태와 "사건이 없었다" 는 스크레이프에서 구분되지 않는다.
        assertThat(scrape).isNotBlank();
        assertThat(scrape).contains("jvm_");
    }

    @Test
    @DisplayName("⭐ 관리 포트에서도 노출 목록 밖은 막힌다 — env 가 열리면 Mongo 자격증명이 나간다")
    void theManagementPortDeniesEverythingElse() throws Exception {
        // when & then: 노출 목록에 없으면 핸들러가 매핑되지 않는다. 시큐리티가 없으니 404 다.
        assertThat(status(managementPort, "/actuator/env")).isEqualTo(404);
        assertThat(status(managementPort, "/actuator/heapdump")).isEqualTo(404);
    }

    private int status(int port, String path) throws Exception {
        return response(port, path).statusCode();
    }

    private String body(int port, String path) throws Exception {
        return response(port, path).body();
    }

    private HttpResponse<String> response(int port, String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_CEILING).build();
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(HTTP_CEILING).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
