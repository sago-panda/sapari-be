package com.sapari.streamingapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@SpringBootTest
class StreamingAppApplicationTest {

    @Autowired
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    @Autowired
    private ReactiveMongoTemplate reactiveMongoTemplate;

    @Test
    @DisplayName("컨텍스트가 로드되고 리액티브 Redis·Mongo 템플릿 빈이 준비된다")
    void contextLoadsWithReactiveTemplates() {
        assertThat(reactiveStringRedisTemplate).isNotNull();
        assertThat(reactiveMongoTemplate).isNotNull();
    }
}
