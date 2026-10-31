package com.sapari.streamingapp;

import java.time.Duration;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import com.sapari.chat.application.port.ChatAccountEventSource;
import com.sapari.chat.application.handler.ChatAccountEventHandler;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 이 앱이 실제로 뜨는지만 본다 — 가장 싼 부팅 가드다.
 *
 * <p>컨테이너를 띄우는 이유: 컨텍스트에는 시작하자마자 붙는 것들이 있다. {@code RedisChatBroadcaster}는
 * 생성자에서 구독을 열고, {@code ChatMongoConfig}는 빈 생성 단계에서 인덱스를 만든다(포트가 열리기
 * 전에 끝내려고 일부러 그 자리다 — {@code EagerSingletonsRunBeforeThePortBindsTest}). 인프라 없이 돌리면
 * 이 테스트는 <b>언제나 빨간불</b>이 되고, 그러면 무엇도 지키지 못한다 — 부팅이 실제로 깨진 날에도
 * 평소와 똑같아 보인다.
 *
 * <p>주소를 프로퍼티로 적지 않고 {@code @ServiceConnection}에 맡긴다. 이름을 적어 두면 그 이름이 바뀌는
 * 순간 조용히 기본값으로 돌아가는데, Boot 4의 {@code spring.data.mongodb.*} → {@code spring.mongodb.*}
 * 개명에서 실제로 그 일이 있었다.
 *
 * <p>룸 토큰 공개키를 여기서 만들어 넣는다. 그 설정은 <b>필수</b>라 없으면 부팅이 실패하는데
 * ({@code RoomTokenConfig}), 운영 설정 파일은 추적되지 않으므로 CI 에는 없다. 이 테스트가 재려는 것은
 * "인프라를 갖추면 뜨는가"이지 "설정 파일이 있는가"가 아니라서, 키는 테스트가 공급한다 —
 * <b>아무 RSA 공개키여도 된다</b>(이 테스트는 토큰 서명을 검증하지 않는다). 다만 파싱은 되어야 한다 —
 * 더미 문자열을 넣으면 가드가 거부해 컨텍스트가 뜨지 않는다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("streaming-app — 인프라를 갖추면 컨텍스트가 뜬다")
class StreamingAppApplicationTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        registry.add("chat.room-token.public-key", StreamingAppApplicationTest::throwawayPublicKey);
    }

    private static String throwawayPublicKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return Base64.getEncoder().encodeToString(gen.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 를 쓸 수 없다", e);
        }
    }

    @Autowired
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @Autowired
    private ReactiveMongoTemplate reactiveMongoTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("컨텍스트가 로드되고 리액티브 Redis·Mongo 템플릿 빈이 준비된다")
    void contextLoadsWithReactiveTemplates() {
        // when & then
        assertThat(reactiveStringRedisTemplate).isNotNull();
        assertThat(reactiveMongoTemplate).isNotNull();
    }

    @Test
    @DisplayName("⭐ 계정 조치 구독이 이 앱에 산다 — 없으면 밴이 조용히 아무 세션도 못 끊는다")
    void accountEventConsumerIsWired() {
        // when & then: 이 둘은 @Component 하나로만 붙어 있다. 스테레오타입이 떨어지거나 스캔 범위가
        // 좁아지면 컨텍스트는 멀쩡히 뜨고 빌드도 초록인데, 밴 이벤트를 아무도 안 받는다 —
        // 기능이 통째로 no-op이 되는데 실패하는 것이 하나도 없다(실제로 그 상태였다).
        assertThat(context.getBean(ChatAccountEventSource.class)).isNotNull();
        assertThat(context.getBean(ChatAccountEventHandler.class)).isNotNull();
    }

    @Test
    @DisplayName("관계형 DB 없이도 뜬다 — chat-core가 끌고 오는 JPA가 이 앱을 막지 않는다")
    void bootsWithoutARelationalDatabase() {
        // when & then: DataSource 자동설정 제외가 풀리면 여기서 컨텍스트가 아예 못 뜬다
        assertThat(reactiveMongoTemplate.getMongoDatabase().block(VERIFY_CEILING)).isNotNull();
    }
}
