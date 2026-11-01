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
        // ⭐ env 를 <b>일부러</b> 노출 목록에 넣는다(픽스처는 health,prometheus 만 준다).
        // 그래야 핸들러가 매핑되고, 그때 그걸 막는 것은 actuatorFilterChain 의 denyAll 하나다 —
        // 즉 이 한 줄이 denyAll 분기를 테스트 사정권으로 끌어온다. 노출 가드가 live-app 에서
        // include: "*" 를 통과시키는 근거가 그 denyAll 인데, 그 근거를 밟는 테스트가 없었다.
        registry.add("management.endpoints.web.exposure.include", () -> "health,prometheus,env");
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
        //
        // prometheus 는 200 을 못박는다 — 외부 의존이 없어서 "닿는가" 와 "200 인가" 가 같은 질문이다.
        assertThat(status(managementPort, "/actuator/prometheus")).isEqualTo(200);
        // health 는 200 을 요구하지 않는다. 이 집계는 컨테이너 기반 지표(DB·Redis·Mongo)를 포함해서
        // 의존 하나가 일시적으로 DOWN 이면 503 이 되는데, 그건 <b>인증 문제가 아니다</b> — 실측:
        // 단독 실행은 200(UP)이고 전체 빌드에서 컨테이너가 경합할 때 503 이 나왔다. 200 을 못박으면
        // 이 테스트가 재려는 것(무인증 접근)과 무관한 이유로 빨개진다.
        //
        // 그래도 넓게 받지 않는다: 401·403 은 인증 실패라서 이 테스트의 대상이고, 404 는 엔드포인트가
        // 사라진 것이다. 둘 다 여기서 걸린다. 의존이 실제로 UP 인지는 LiveAppApplicationTests 가 본다.
        assertThat(status(managementPort, "/actuator/health"))
                .as("health 가 401·403 이면 스크레이퍼·프로브가 막힌 것이고, 404 면 엔드포인트가 없다")
                .isIn(200, 503);
    }

    @Test
    @DisplayName("⭐ 노출 목록에 든 것도 체인이 막는다 — denyAll 이 유일한 방어이고 이 단언이 그걸 밟는다")
    void managementPort_deniesAnExposedEndpointThatIsNotPermitted() throws Exception {
        // env 는 위 @DynamicPropertySource 가 일부러 노출했다. 핸들러가 매핑됐으므로 기본 체인의
        // authenticated() 가 아니라 액추에이터 체인의 denyAll 이 잡아야 한다 → 403.
        //
        // 전에는 이 자리가 isIn(401, 403) 이었고 env 가 미매핑이라 401 로 통과했다. 그래서
        // denyAll 을 지워도 빌드가 초록이었다 — 넓게 받는 단언이 "둘 중 아무거나" 를 넘어 "둘 다
        // 아니어도" 를 허용한 셈이다.
        //
        // 무엇을 막는 것인가(이 커밋의 앞 버전이 틀리게 적었다): /actuator/env 응답의 값은
        // 가려진다 — 실측(바이트코드): show-values 기본값이 Show.NEVER 이고 Sanitizer 는 그때
        // "******" 를 무조건 돌려준다. 그래서 여기서 막는 것은 시크릿 값이 아니라 프로퍼티
        // 이름·소스 전부이고, 그 마스킹 자체가 설정 한 줄로 뒤집히는 기본값이다. 평문 시크릿은
        // /heapdump 쪽이고 그것도 같은 denyAll 이 막는다.
        assertThat(status(managementPort, "/actuator/env")).isEqualTo(403);
        // 액추에이터 <b>기본 경로</b>(링크 목록)도 같은 체인에 걸린다 — 실측: 403. 노출 목록에
        // 없어도 toAnyEndpoint() 가 기본 경로 자체를 매칭하기 때문이다. 링크 목록은 어떤
        // 엔드포인트가 사는지를 알려 주므로 이쪽도 열려서는 안 된다.
        assertThat(status(managementPort, "/actuator")).isEqualTo(403);
    }

    @Test
    @DisplayName("노출 목록 밖은 매핑조차 안 된다 — 기본 체인의 authenticated() 에 걸려 401")
    void managementPort_deniesAnUnexposedEndpoint() throws Exception {
        // 위 테스트와 차단 주체가 다르다. 여기는 핸들러가 없어서 막히고, 위는 핸들러가 있는데
        // 막힌다 — 두 방어를 한 단언에 섞으면 둘 중 하나가 사라진 것을 알 수 없다.
        // 실측: beans 401(존재하지만 미노출) · nope 401(없는 경로). 둘이 같은 코드인 것이 이 방어의
        // 성질이다 — 미노출 엔드포인트와 오타를 구별해 주지 않으므로 엔드포인트 열거가 안 된다.
        assertThat(status(managementPort, "/actuator/beans")).isEqualTo(401);
        assertThat(status(managementPort, "/actuator/nope")).isEqualTo(401);
    }

    private int status(int port, String path) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
