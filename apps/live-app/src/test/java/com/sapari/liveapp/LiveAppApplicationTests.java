package com.sapari.liveapp;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 컨텍스트가 뜨는지만 본다.
 *
 * <p>설정을 여기서 직접 채우는 이유: live-app은 DB·LiveKit·룸 토큰 설정을 필수(@NotBlank/@NotNull)로
 * 바인딩하는데, 그 값들이 담긴 {@code application.yml}은 자격증명 때문에 저장소에 올리지 않는다
 * (.gitignore가 {@code application*.yml}을 전부 막는다). 그래서 리소스 파일로 두면 이 테스트는
 * 각자 로컬에만 초록이고 클린 체크아웃에서는 계속 빨갛다 — 실제로 그런 상태였다.
 *
 * <p><b>DB에 접속하지 않는다.</b> 방언을 명시해 Hibernate가 방언 판별을 위해 커넥션을 여는 것을 막고,
 * {@code ddl-auto=none}으로 스키마 작업도 하지 않는다. 실제 쿼리를 도는 테스트가 생기면 그 테스트가
 * TestContainers를 직접 띄워야 한다 — 여기 URL을 실 DB로 바꾸지 말 것.
 *
 * <p>룸 토큰 개인키는 <b>실행할 때마다 새로 만든다.</b> 저장소에 개인키 모양의 문자열을 남기지 않기
 * 위해서다(스캐너가 잡고, 다음 사람이 진짜 키로 오해한다). 값이 무엇이든 상관없고 형식만 맞으면 된다 —
 * 이 테스트는 서명 결과를 검증하지 않는다.
 */
@SpringBootTest
class LiveAppApplicationTests {

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/sapari_test");
        registry.add("spring.datasource.username", () -> "test");
        registry.add("spring.datasource.password", () -> "test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("jwt.issuer", () -> "sapari-test");
        registry.add("jwt.secret", () -> "test-only-secret-not-a-credential-32chars");
        registry.add("jwt.access-token-expiration-seconds", () -> 3600);
        registry.add("jwt.refresh-token-expiration-seconds", () -> 1209600);

        registry.add("live.room-token.issuer", () -> "live");
        registry.add("live.room-token.audience", () -> "chat");
        registry.add("live.room-token.expiration-seconds", () -> 90);
        registry.add("live.room-token.private-key", LiveAppApplicationTests::generatedPrivateKey);

        registry.add("livekit.host", () -> "http://localhost:7880");
        registry.add("livekit.api-key", () -> "test-key");
        registry.add("livekit.api-secret", () -> "test-secret");
        registry.add("livekit.s3.bucket", () -> "test-bucket");
        registry.add("livekit.s3.region", () -> "ap-northeast-2");
        registry.add("livekit.s3.key-prefix", () -> "test/");
        registry.add("livekit.s3.access-key", () -> "test-access");
        registry.add("livekit.s3.secret-key", () -> "test-secret");
        registry.add("livekit.hls.cdn-base-url", () -> "http://localhost/hls");
        registry.add("livekit.hls.segment-duration", () -> 4);
    }

    /** Base64 PKCS#8 — RoomTokenConfig가 기동 시 파싱하는 형식. */
    private static String generatedPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 룸 토큰 키 생성 실패", e);
        }
    }

    @Test
    void contextLoads() {
    }

}
