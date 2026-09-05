package com.sapari.liveapp.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

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
 * <p><b>이 테스트는 CI 에서 돌지 않는다.</b> 실제 컨텍스트가 필요해 {@code context} 태그를 달았고,
 * 루트 {@code build.gradle} 이 CI 에서 그 태그를 제외한다(클론에 {@code application*.yaml} 이 없어
 * 부팅 자체가 불가능하다). 즉 이 테스트가 지키는 건 <b>로컬에서 이 영역을 만지는 사람</b>이지
 * 파이프라인이 아니다 — 회귀를 자동으로 막아주지 않는다.
 *
 * <p>CI 에서 돌게 하려면 데이터소스를 가짜로 바꿔야 하는데, 그러면 "운영과 같은 구성" 이라는 이 테스트의
 * 유일한 값어치가 사라진다. 그 거래는 하지 않았다.
 */
@Tag("context")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ActuatorNotOnUserPortTest {

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
