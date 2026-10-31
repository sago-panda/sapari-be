package com.sapari.liveapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.liveapp.support.LiveAppContextFixture;

/**
 * 이 앱이 실제로 뜨는지만 본다 — 가장 싼 부팅 가드다.
 *
 * <p><b>CI 에서도 돈다.</b> 종전에는 {@code @Tag("context")} 로 제외돼 있었고 그 근거가 "룸토큰
 * 개인키를 CI 에 넣어야 한다" 였는데, 그 키는 외부 의존 없는 프로퍼티라 테스트가 만들어 넣으면 된다
 * ({@code LiveAppContextFixture}). 인프라는 컨테이너가, 스키마는 운영 마이그레이션이 세운다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("live-app — 인프라를 갖추면 컨텍스트가 뜬다")
class LiveAppApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    /**
     * 이 앱은 강퇴 증거 원문을 Mongo 에서 읽는다({@code ChatMessageEvidenceMongoRepository}).
     *
     * <p>컨테이너 없이도 컨텍스트는 뜬다 — 동기 Mongo 템플릿은 느긋하게 접속한다. 그런데
     * {@code /actuator/health} 는 지표를 실제로 찔러서 <b>503</b> 이 된다(실측). 즉 health 가 Mongo 에
     * 걸려 있다는 사실이 이 테스트를 CI 로 옮기면서 드러났다.
     */
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        LiveAppContextFixture.register(registry);
    }

    @Test
    @DisplayName("컨텍스트가 로드된다")
    void contextLoads() {
    }
}
