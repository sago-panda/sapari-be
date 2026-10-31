package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * chat_messages 인덱스가 <b>이 프로세스가 트래픽을 받기 전에</b> 서는지 본다.
 *
 * <p>한때 인덱스를 {@code ApplicationRunner}에서 만들었다. 러너는 컨텍스트 refresh 가 <b>끝난 뒤</b>
 * 돌고, 리액티브 웹서버는 그 refresh 안에서 포트를 bind 한다 — 그래서 러너가 Mongo 를 기다리는 동안
 * 포트는 이미 열려 있었다(실측: {@code EagerSingletonsRunBeforeThePortBindsTest}). 인덱스 셋에
 * 서버 선택 상한 30초가 각각 걸리므로 최악 90초 동안, 이 Pod 는 TCP 프로브에 멀쩡히 Ready 로
 * 답하면서 인덱스 없는 컬렉션으로 들어온 요청을 받는다.
 *
 * <p>그래서 단언을 "컨텍스트가 뜬 뒤 인덱스가 있다"로 두지 않는다 — 러너 버전도 그건 통과한다.
 * <b>phase {@code MIN_VALUE} lifecycle 이 start 될 때</b> 이미 있었는지를 본다. lifecycle 은 phase
 * 오름차순으로 시작되고 웹서버 phase 는 2,147,481,599 라, 그 시점에 참인 것은 포트가 열리기 전에 참이다.
 */
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Testcontainers
@DisplayName("chat_messages 인덱스 — 포트가 열리기 전에 선다")
class ChatMongoIndexBootstrapTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    /** {@code RedisChatBroadcaster}가 생성자에서 구독을 열어 컨테이너 없이는 컨텍스트가 못 뜬다. */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static final AtomicReference<List<String>> indexesAtEarliestLifecycle = new AtomicReference<>();

    @TestConfiguration
    static class CaptureConfig {

        /**
         * phase {@code MIN_VALUE} — 모든 lifecycle 중 가장 먼저, 즉 포트 bind 전에 start 된다.
         * 그 순간 Mongo 에 실제로 있던 인덱스 이름을 박제한다.
         */
        @Bean
        SmartLifecycle indexSnapshotBeforeAnyLifecycle(ReactiveMongoTemplate mongoTemplate) {
            return new SmartLifecycle() {
                @Override
                public void start() {
                    indexesAtEarliestLifecycle.set(mongoTemplate.indexOps(ChatMessageDocument.class)
                            .getIndexInfo()
                            .map(IndexInfo::getName)
                            .collectList()
                            .block(VERIFY_CEILING));
                }

                @Override
                public void stop() {
                    // 박제만 한다 — 멈출 것이 없다
                }

                @Override
                public boolean isRunning() {
                    return false;
                }

                @Override
                public int getPhase() {
                    return Integer.MIN_VALUE;
                }
            };
        }
    }

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Test
    @DisplayName("⭐ 포트가 열리기 전에 인덱스 3종이 이미 서 있다")
    void indexesExistBeforeTheProcessCanServeTraffic() {
        // given: 이름을 하드코딩하지 않는다 — 생성이 멱등이라 같은 호출이 같은 이름을 돌려준다
        List<String> expected = ChatMongoConfig.createChatMessagesIndexes(mongoTemplate);

        // when & then
        assertThat(indexesAtEarliestLifecycle.get())
                .as("phase MIN_VALUE lifecycle 이 start 될 때 Mongo 에 있던 인덱스")
                .containsAll(expected);
    }
}
