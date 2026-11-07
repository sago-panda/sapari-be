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
import java.util.concurrent.TimeUnit;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
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
 * 그 창 동안 이 Pod 는 인덱스 없는 컬렉션으로 들어온 요청을 받았다. 창의 길이는 한때 "최악 90초"
 * 라고 적었는데 드라이버 기본값이 {@code socketReadTimeoutMs=0}(무한)이라 상한이 없었고, 지금은
 * {@code ChatMongoConfig.INDEX_CREATE_CEILING} 이 네 인덱스 <b>전체</b>에 하나로 걸린다.
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
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    /** {@code RedisChatBroadcaster}가 생성자에서 구독을 열어 컨테이너 없이는 컨텍스트가 못 뜬다. */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

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

    @Autowired
    private ReactiveMongoDatabaseFactory databaseFactory;

    @Test
    @DisplayName("⭐ 전송 경로와 인덱스 경로가 서로 다른 상한을 받는다 — 둘이 같으면 하나가 안 걸린 것이다")
    void theSendPathAndTheIndexPathCarryDifferentTimeouts() {
        Long sendPath = databaseFactory.getMongoDatabase().block(VERIFY_CEILING)
                .getTimeout(TimeUnit.MILLISECONDS);
        Long indexPath = ChatMongoConfig.indexTimeoutFactory(databaseFactory, Duration.ofMinutes(10))
                .getMongoDatabase().block(VERIFY_CEILING).getTimeout(TimeUnit.MILLISECONDS);

        // 전송 경로: 커스터마이저가 건 기본값. null 이면 상한이 아예 안 걸렸다는 뜻이고 그게 SPR-177
        // 전의 상태다(드라이버 기본 timeoutMs=null, 실측).
        assertThat(sendPath)
                .as("전송 경로에 상한이 없다 — 느린 Mongo 가 세션을 수명까지 멈춘다")
                .isEqualTo(Duration.ofSeconds(2).toMillis());
        // 인덱스 경로: 되올린 값. 전역과 **달라야** 한다 — 같으면 디코레이터가 전역을 못 덮은 것이고
        // 큰 컬렉션의 첫 인덱스 빌드가 2초에 죽어 부팅이 실패한다(실측: 전역 500ms 에서 3,000ms
        // 차단된 createIndexes 가 563ms 에 끊겼다).
        assertThat(indexPath)
                .as("인덱스 경로가 전역 상한을 그대로 받았다 — 부팅이 2초에 죽는다")
                .isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    /**
     * ⚠️ <b>위 칸이 덮지 못하는 것</b>: {@code chatMessagesIndexes} 빈이 <b>실제로</b> 그 되올린
     * 팩토리를 쓰는지. 이 칸은 디코레이터를 직접 불러 그 효과를 보고, 빈 쪽 배선은
     * {@code ChatMongoConfig:227} 을 읽어야 안다. 빈이 돌려주는 {@code ChatMessagesIndexes} 는
     * 이름 목록뿐이라 어느 상한에서 만들었는지가 거기 안 남는다 — 그걸 남기려면 부팅에 왕복을
     * 하나 더 넣어야 해서, 테스트를 위해 운영 경로를 늘리지 않았다. 분모가 비어 있다는 사실을
     * 적어 두는 쪽을 골랐다.
     */
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
