package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 연산 상한이 <b>실제 드라이버</b>에서 두 값으로 갈린다 — 전송 경로는 설정값, 인덱스 경로는 되올린 값.
 *
 * <p>왜 이 칸이 따로 필요한가. {@code ChatMongoTimeoutConfigTest} 는 커스터마이저와 디코레이터를
 * 각각 손으로 조립해 본다. 여기서 재는 것은 그 둘이 <b>붙어서</b> 실제 클라이언트에 어떤 값으로
 * 나타나는지다 — 커스터마이저가 안 걸리거나 디코레이터가 전역을 못 덮으면 두 값이 같아진다.
 *
 * <p><b>실측으로 정한 설계라는 것도 여기 적어 둔다.</b> 전역 CSOT 는 {@code createIndexes} 까지
 * 자른다(전역 500ms 에서 3,000ms 차단된 createIndexes 가 563ms 에
 * {@code InterruptedByTimeoutException}, 전역이 없으면 3,104ms 완료). 그런데 Spring Data 5.0.5 의
 * {@code ReactiveIndexOperations}·{@code ReactiveMongoTemplate}·{@code Query} 어디에도 연산 단위
 * 상한을 받는 API 가 없다(전수). 그래서 팩토리를 감싸 그 템플릿만 되올린다.
 *
 * <p>커넥션 <b>반납</b>은 이 칸이 재지 않는다 — 그건 드라이버의 행동이고, 설계를 고를 때
 * failpoint + 풀=1 로 이미 쟀다(전역 CSOT 는 끊고 반납해 두 번째 연산이 22ms, 호출부
 * {@code .timeout()} 은 반납하지 않아 2,521ms). 그 수를 CI 로 옮기면 failpoint 와 풀 고정이 필요해
 * 비용과 불안정이 같이 올라가므로, 이쪽은 <b>우리 코드가 어느 상한을 싣는지</b>만 고정한다.
 */
// 형제 ChatMessageRepositoryImplTest 와 같은 이유로 DataSource 자동설정을 끈다 — 강퇴 로그 때문에
// 모듈에 JPA 가 들어오면서 Mongo 만 쓰는 칸까지 관계형 DB 를 요구하게 됐다.
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Testcontainers
@DisplayName("Mongo 연산 상한 — 실제 드라이버에서 전송과 인덱스가 다른 값을 받는다")
class ChatMongoTimeoutIntegrationTest {

    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    /**
     * 이 칸은 Redis 를 쓰지 않는다. 그런데 컨텍스트에 {@code RedisChatBroadcaster} 가 함께 올라오고
     * 그 어댑터는 생성자에서 구독을 연다 — 형제 {@code ChatMessageRepositoryImplTest} 가 같은 이유로
     * 같은 컨테이너를 띄운다.
     */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Autowired
    private ReactiveMongoDatabaseFactory factory;

    @Test
    @DisplayName("⭐ 인덱스 경로는 되올린 상한을 받고 전송 경로는 설정값을 받는다 — 둘이 같으면 하나가 안 걸린 것이다")
    void theTwoPathsCarryDifferentTimeouts() {
        Long sendPath = factory.getMongoDatabase().block(VERIFY_CEILING).getTimeout(TimeUnit.MILLISECONDS);
        Long indexPath = ChatMongoConfig
                .indexTimeoutFactory(factory, Duration.ofMinutes(10))
                .getMongoDatabase().block(VERIFY_CEILING)
                .getTimeout(TimeUnit.MILLISECONDS);

        // 전송 경로: 커스터마이저가 건 기본값(2s). null 이면 상한이 아예 안 걸렸다는 뜻이고,
        // 그게 이 티켓 전의 상태다(드라이버 기본 timeoutMs=null, 실측).
        assertThat(sendPath)
                .as("전송 경로에 상한이 없다 — 느린 Mongo 가 세션을 수명까지 멈춘다")
                .isEqualTo(Duration.ofSeconds(2).toMillis());

        // 인덱스 경로: 되올린 값. 전송 경로와 **달라야** 한다 — 같으면 디코레이터가 전역을 못 덮은 것이고,
        // 그러면 큰 컬렉션의 첫 인덱스 빌드가 2초에 죽어 부팅이 실패한다(오늘은 10분을 기다린다).
        assertThat(indexPath)
                .as("인덱스 경로가 전역 상한을 그대로 받았다 — 부팅이 2초에 죽는다")
                .isEqualTo(Duration.ofMinutes(10).toMillis());
    }
}
