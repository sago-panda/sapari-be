package com.sapari.streamingapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * 생성자에서 구독을 열고, {@code ChatMongoConfig}는 시작 러너에서 인덱스를 만든다. 인프라 없이 돌리면
 * 이 테스트는 <b>언제나 빨간불</b>이 되고, 그러면 무엇도 지키지 못한다 — 부팅이 실제로 깨진 날에도
 * 평소와 똑같아 보인다.
 *
 * <p>주소를 프로퍼티로 적지 않고 {@code @ServiceConnection}에 맡긴다. 이름을 적어 두면 그 이름이 바뀌는
 * 순간 조용히 기본값으로 돌아가는데, Boot 4의 {@code spring.data.mongodb.*} → {@code spring.mongodb.*}
 * 개명에서 실제로 그 일이 있었다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("streaming-app — 인프라를 갖추면 컨텍스트가 뜬다")
class StreamingAppApplicationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @Autowired
    private ReactiveMongoTemplate reactiveMongoTemplate;

    @Test
    @DisplayName("컨텍스트가 로드되고 리액티브 Redis·Mongo 템플릿 빈이 준비된다")
    void contextLoadsWithReactiveTemplates() {
        // when & then
        assertThat(reactiveStringRedisTemplate).isNotNull();
        assertThat(reactiveMongoTemplate).isNotNull();
    }

    @Test
    @DisplayName("관계형 DB 없이도 뜬다 — chat-core가 끌고 오는 JPA가 이 앱을 막지 않는다")
    void bootsWithoutARelationalDatabase() {
        // when & then: DataSource 자동설정 제외가 풀리면 여기서 컨텍스트가 아예 못 뜬다
        assertThat(reactiveMongoTemplate.getMongoDatabase().block()).isNotNull();
    }
}
