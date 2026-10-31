package com.sapari.liveapp.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.liveapp.support.LiveAppContextFixture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포트별로 무엇이 열리고 무엇이 막히는지를 <b>양방향으로</b> 고정한다 — 사용자 포트에는 없고,
 * 관리 포트에는 딱 두 개만 있다.
 *
 * <p>{@code actuatorFilterChain} 이 액추에이터를 {@code permitAll} 하는 근거가 "관리 포트가 따로라
 * 사용자 포트에는 매핑되지 않는다" 하나뿐인데, 그건 스프링 부트의 동작이지 우리가 쓴 코드가 아니다.
 * 부트 버전이 올라가며 그 동작이 바뀌면 <b>지표가 사용자 포트에 조용히 열린다</b> — 아무 에러도
 * 나지 않으므로 여기서 잡지 못하면 아무도 모른다.
 *
 * <p>기대값이 404 가 아니라 <b>401</b> 인 것에 유의: 경로가 매핑되지 않았어도 시큐리티 체인이 먼저
 * 돌아 {@code anyRequest().authenticated()} 에 걸린다. 404 로 단언하면 이 테스트가 틀린다.
 *
 * <p><b>이 테스트는 이제 CI 에서도 돈다</b>(2026-10-31). 종전에는 {@code @Tag("context")} 로 제외돼
 * 있었고 그 근거가 "설정을 CI 변수로 채우는 길은 룸토큰 <b>개인키</b>를 CI 에 넣는 것" 이었는데,
 * 그 키는 외부 의존이 없는 프로퍼티라 <b>테스트가 키쌍을 만들어 넣으면 된다</b>. 인프라는 컨테이너가,
 * 스키마는 운영 마이그레이션(Flyway)이 세운다 — 데이터소스를 가짜로 바꾸지 않았으므로 "운영과 같은
 * 구성" 이라는 이 테스트의 값어치도 그대로다.
 *
 * <p>그 전까지 이 가드는 <b>파이프라인에서 비어 있었다</b> — 포트가 합쳐져도 빌드가 초록이었고,
 * 열리는 것은 영업 물량과 스크레이프마다 DB 를 치는 게이지다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ActuatorNotOnUserPortTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    /**
     * 이 앱은 강퇴 증거 원문을 Mongo 에서 읽는다({@code ChatMessageEvidenceMongoRepository}).
     *
     * <p>컨테이너 없이도 컨텍스트는 뜬다 — 동기 Mongo 템플릿은 느긋하게 접속한다. 그런데
     * {@code /actuator/health} 는 지표를 실제로 찔러서 <b>503</b> 이 된다(실측). 즉 health 가 Mongo 에
     * 걸려 있다는 사실이 이 테스트를 CI 로 옮기면서 드러났다.
     */
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        LiveAppContextFixture.register(registry);
    }

    @LocalServerPort
    private int userPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("사용자 포트에서는 지표를 받을 수 없다")
    void userPort_doesNotServeMetrics() throws Exception {
        assertThat(status(userPort, "/actuator/prometheus")).isEqualTo(401);
        assertThat(status(userPort, "/actuator/health")).isEqualTo(401);
    }

    @Test
    @DisplayName("관리 포트에서는 인증 없이 지표를 받을 수 있다 — 스크레이퍼는 로그인 수단이 없다")
    void managementPort_servesMetricsWithoutAuth() throws Exception {
        // 음성 방향(위 테스트)만 있으면 "둘 다 막힘" 도 통과한다. 그건 지표가 조용히 비는 상태이고,
        // 대시보드가 빈 것이 "방송이 없어서" 와 구분되지 않는다 — 이 설계가 가장 두려워한 실패다.
        assertThat(status(managementPort, "/actuator/prometheus")).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/health")).isEqualTo(200);
    }

    @Test
    @DisplayName("관리 포트에서도 노출 목록 밖은 막힌다 — 401(미매핑)이든 403(denyAll)이든 200 이 아니다")
    void managementPort_deniesEverythingElse() throws Exception {
        // 차단 코드가 설정에 따라 갈린다. 노출 목록에 없으면 핸들러가 매핑되지 않아 기본 체인의
        // authenticated() 에 걸려 401 이고, 목록에 넣으면 액추에이터 체인의 denyAll 이 잡아 403 이다.
        // 여기서 중요한 건 코드가 아니라 "둘 중 무엇이든 200 이 아니다" 이므로 둘 다 받는다 —
        // 하나로 못박으면 노출 설정을 만질 때마다 이 테스트가 엉뚱하게 깨진다.
        assertThat(status(managementPort, "/actuator/env")).isIn(401, 403);
        assertThat(status(managementPort, "/actuator")).isIn(401, 403);
    }

    private int status(int port, String path) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
