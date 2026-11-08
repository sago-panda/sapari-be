package com.sapari.liveapp.support;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * live-app 컨텍스트를 <b>CI 에서도</b> 띄우기 위해 필요한 설정 전부.
 *
 * <p>종전에는 이 앱의 컨텍스트 테스트가 {@code @Tag("context")} 로 CI 에서 제외돼 있었고, 루트
 * {@code build.gradle} 이 그 근거를 "설정을 CI 변수로 채우는 길은 <b>룸토큰 개인키를 CI 에 넣는
 * 것</b>이라 택하지 않았다" 로 적어 뒀다. <b>그 전제가 틀렸다</b> — 룸 토큰 키는 외부 의존이 없는
 * 프로퍼티라 테스트가 키쌍을 만들어 넣으면 된다. 서명을 검증하는 테스트가 아니므로 아무 RSA 키여도
 * 되고(파싱만 되면 된다), 그러면 시크릿 없이 CI 에서 돈다.
 *
 * <p>그게 중요한 이유: 이 앱에서 CI 에 가려져 있던 가드 중 하나가 <b>지표가 사용자 포트에 열리지
 * 않는다</b>였다. 그 엔드포인트는 영업 물량을 담고 게이지가 스크레이프마다 DB 를 친다 — 즉 그 가드가
 * 파이프라인에서 비어 있는 동안, 포트가 합쳐져도 빌드는 초록이었다.
 *
 * <p>LiveKit 값은 더미다. 부팅 시점에 접속하지 않고(프로퍼티만 들고 있다) 정리 잡은 아래에서 끈다 —
 * 스케줄러가 돌면 테스트가 LiveKit 에 실제로 가려 한다.
 */
public final class LiveAppContextFixture {

    private LiveAppContextFixture() {
    }

    /** Flyway 가 읽을 운영 마이그레이션 디렉터리. 테스트가 SQL 을 손수 읽지 않는 이유가 이것이다. */
    public static String migrationLocation() {
        // 작업 디렉터리는 모듈(apps/live-app)이라 저장소 루트로 두 단계 올라간다.
        return "filesystem:" + Path.of("..", "..", "db", "migration", "live").toAbsolutePath().normalize();
    }

    /**
     * 부팅에 필요한 전부를 등록한다.
     *
     * <p>{@code management.server.port=0} 은 "임의의 빈 포트" 다 — {@code LiveSecurityConfig} 의 가드가
     * 이 값을 요구하고, 둘 다 0 이면 실행 시점에 서로 다른 포트가 잡히므로 같은 포트가 아니다.
     */
    public static void register(DynamicPropertyRegistry registry) {
        KeyPair roomTokenKeys = generateRsaKeyPair();

        // 접두가 live.room-token 이다(streaming-app 쪽은 chat.room-token — 발급과 검증이 다른 앱이다).
        registry.add("live.room-token.issuer", () -> "sapari-live-test");
        registry.add("live.room-token.audience", () -> "sapari-chat");
        registry.add("live.room-token.private-key",
                () -> Base64.getEncoder().encodeToString(roomTokenKeys.getPrivate().getEncoded()));
        registry.add("live.room-token.expiration-seconds", () -> "90");

        // 더미 값. 부팅 시점에 접속하지 않는다(프로퍼티만 들고 있다). host 가 http 인데도 통과하는
        // 것은 루프백이 예외이기 때문이다 — 운영 값이 https 여야 한다는 가드는 그대로 살아 있다.
        registry.add("livekit.host", () -> "http://localhost:7880");
        registry.add("livekit.api-key", () -> "devkey");
        registry.add("livekit.api-secret", () -> "devsecret-devsecret-devsecret-32");
        registry.add("livekit.s3.bucket", () -> "test-bucket");
        registry.add("livekit.s3.region", () -> "ap-northeast-2");
        registry.add("livekit.s3.key-prefix", () -> "hls/");
        registry.add("livekit.s3.access-key", () -> "test-access-key");
        registry.add("livekit.s3.secret-key", () -> "test-secret-key");
        registry.add("livekit.hls.cdn-base-url", () -> "https://cdn.example.test");
        registry.add("livekit.hls.segment-duration", () -> "4");
        registry.add("live.thumbnail.bucket", () -> "test-bucket");
        registry.add("live.thumbnail.region", () -> "ap-northeast-2");
        registry.add("live.thumbnail.access-key", () -> "test-access-key");
        registry.add("live.thumbnail.secret-key", () -> "test-secret-key");
        registry.add("live.thumbnail.cdn-base-url", () -> "https://cdn.example.test");

        // api-app 이 발급한 access 토큰을 이 앱이 검증한다(StatelessJwtAuthenticationFilter).
        // 시크릿은 32자 이상이어야 한다 — 더미여도 길이 제약은 실제 규칙이다.
        registry.add("jwt.issuer", () -> "sapari-test");
        registry.add("jwt.secret", () -> "test-secret-test-secret-test-secret-32");
        registry.add("jwt.access-token-expiration-seconds", () -> "1800");
        registry.add("jwt.refresh-token-expiration-seconds", () -> "604800");

        // 정리 잡을 끈다 — 돌면 LiveKit 에 실제로 가려 한다.
        registry.add("live.reconcile.enabled", () -> "false");

        // 스키마는 운영 마이그레이션으로 세운다. 스키마 생성까지 Flyway 에 맡긴다 —
        // 컨테이너에는 live_schema 가 없다.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", LiveAppContextFixture::migrationLocation);
        registry.add("spring.flyway.schemas", () -> "live_schema");
        registry.add("spring.flyway.create-schemas", () -> "true");

        registry.add("management.server.port", () -> "0");
        // ⚠️ 이 줄이 빠지면 액추에이터 기본 노출이 health 하나라 prometheus 엔드포인트가 아예 없고,
        // 그러면 그 경로가 액추에이터 체인에 매칭되지 않아 기본 체인의 authenticated() 에 걸려
        // <b>401</b> 이 된다(실측 — 이 테스트를 CI 로 옮기자마자 그렇게 깨졌다). 운영 템플릿이 이 값을
        // 담고 있지만 "optional" 로 적혀 있어, 빠뜨리면 지표가 조용히 비는 것과 구분되지 않는다.
        registry.add("management.endpoints.web.exposure.include", () -> "health,prometheus");
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 를 쓸 수 없다", e);
        }
    }
}
