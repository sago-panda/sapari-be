package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.infrastructure.config.ChatMongoConfig;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import reactor.core.publisher.Mono;

/**
 * 중복복구 재조회가 <b>어느 인덱스를 쓰고 몇 문서를 읽는가</b>.
 *
 * <p>왜 이 칸이 필요한가. 형제 칸({@code ChatMessageRepositoryImplTest} 의 인덱스 칸)은 그 인덱스가
 * <b>존재하는지</b>만 단언한다 — 질의가 그걸 <b>쓰는지</b>는 재지 않는다. 그래서 유니크 파셜 인덱스를
 * 못 쓰고 방 전체를 훑는 상태가 전 스위트 초록으로 통과했다(실측: 24,000 docs / 방당 2,000 인 일회용
 * {@code mongo:7.0} 에서 {@code docsExamined 2000 · keysExamined 2000}, 질의에 {@code $type} 절을
 * 더하면 {@code 1 · 1}).
 *
 * <p>비용이 방의 메시지 수에 비례하는 자리가 WS 전송 경로의 {@code concatMap} 안이고 전역 2초 상한
 * 아래다 — 가장 뜨거운 방에서 가장 비싸진다.
 *
 * <p><b>필터를 다시 만들지 않는다.</b> 여기서 질의 문서를 손으로 조립해 {@code explain} 하면 재는
 * 것이 저장소 코드가 아니라 내가 쓴 필터가 된다(동어반복). 그래서 서버 프로파일러를 켜고
 * <b>저장소가 실제로 발행한</b> 연산을 {@code system.profile} 에서 읽는다.
 *
 * <p>컨테이너를 형제와 공유하지 않는 이유: {@code setProfilingLevel} 은 <b>서버 전역</b> 상태라
 * 같은 클래스의 다른 칸이 프로파일 수집에 섞이고, {@code system.profile} 이 그 칸들의 연산까지
 * 들고 있게 된다.
 */
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Testcontainers
class ChatMessageQueryPlanTest {

    /** 반응형 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 멈추게 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    /** 방에 깔아 두는 다른 메시지 수. 1 이면 "전수 스캔" 과 "인덱스 적중" 의 docsExamined 가 같아진다. */
    private static final int OTHER_MESSAGES_IN_ROOM = 20;

    /** 창 안에 깔아 두는 메시지 수. 요청 limit 과 달라야 "창 전수" 와 "limit" 가 구분된다. */
    private static final int MESSAGES_IN_WINDOW = 60;

    /**
     * 창 <b>뒤</b>에 깔아 두는 메시지 수 — 이 수가 없으면 측정 자체가 성립하지 않는다.
     *
     * <p>방의 메시지가 전부 창 안이면 플래너가 페이징 인덱스{@code (roomId,_id)}를 고른다. 그것이
     * 정렬을 공급하므로 blocking SORT 가 없고, 최신 쪽에서 {@code limit} 만큼 읽으면 전부 창에
     * 맞아 {@code docsExamined == limit} 가 <b>고치기 전에도</b> 나온다(실측: 창 안 30건만 두고
     * 돌렸더니 초록이었다). 창 밖 최신 메시지가 많아야 그 계획이 비싸지고, 플래너가
     * {@code (roomId,createdAt)} 를 골라 <b>창 전수를 FETCH 한 뒤 정렬</b>하는 실제 결함 모양이 된다.
     */
    private static final int MESSAGES_AFTER_WINDOW = 240;

    private static final int WINDOW_LIMIT = 5;

    private static final Instant WINDOW_FROM = Instant.parse("2026-06-18T00:00:00Z");

    /**
     * {@code planSummary} 가 싣는 것은 인덱스 <b>이름</b>이 아니라 키 패턴이다 — 실측으로 받은
     * 적색이 {@code "IXSCAN { roomId: 1, _id: 1 }"} 였다. 이름으로 단언하면 고쳐도 통과하지 않는다.
     */
    private static final String DEDUP_INDEX_KEYS = "{ roomId: 1, senderId: 1, clientMsgId: 1 }";

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    /** 이 테스트는 Redis 를 쓰지 않지만 {@code RedisChatBroadcaster} 가 생성자에서 구독을 연다 — 없으면 컨텍스트가 못 뜬다. */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    /** 블로킹 이력 어댑터는 스테레오타입이 없다 — 쓰는 앱이 등록한다. 테스트도 같은 모양으로 등록한다. */
    @TestConfiguration
    static class BlockingAdapterConfig {

        @Bean
        ChatHistoryRepository chatHistoryRepository(MongoTemplate blockingTemplate, ChatMessageMapper mapper) {
            return new ChatHistoryMongoRepository(blockingTemplate, mapper);
        }
    }

    @Autowired
    private ChatMessageRepositoryImpl repository;

    @Autowired
    private ChatHistoryRepository historyRepository;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(ChatMessageDocument.class).block(VERIFY_CEILING);
        ChatMongoConfig.createChatMessagesIndexes(mongoTemplate);
        runCommand(new Document("profile", 0)).block(VERIFY_CEILING);
        mongoTemplate.dropCollection("system.profile").block(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ 중복복구 재조회가 dedup 유니크 파셜 인덱스를 쓰고 1문서만 읽는다")
    void theDuplicateRecoveryReadUsesTheDedupIndex() {
        // given: 같은 방에 다른 clientMsgId 를 여럿 깔아 둔다 — 전수 스캔이면 이 수가 docsExamined 다
        for (int i = 0; i < OTHER_MESSAGES_IN_ROOM; i++) {
            repository.save(message("기타-" + i, "other-" + i)).block(VERIFY_CEILING);
        }
        repository.save(message("원본", "dup-key")).block(VERIFY_CEILING);

        // when: 프로파일러를 켠 구간에서만 재조회를 돌린다
        runCommand(new Document("profile", 2)).block(VERIFY_CEILING);
        ChatMessage recovered = repository
                .findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "dup-key").block(VERIFY_CEILING);
        runCommand(new Document("profile", 0)).block(VERIFY_CEILING);

        // then: 먼저 그 연산이 **잡혔는지** 본다 — 안 잡혔으면 아래 단언의 분모가 0 이다
        Document profiled = lastRecoveryRead();
        assertThat(profiled)
                .as("프로파일러가 재조회 연산을 못 잡았다 — 아래 수치의 분모가 0 이다")
                .isNotNull();
        assertThat(recovered.displayMessage()).isEqualTo("원본");

        assertThat(profiled.getString("planSummary"))
                .as("dedup 유니크 파셜 인덱스를 쓰지 않는다 — 비용이 방의 메시지 수에 비례한다")
                .contains(DEDUP_INDEX_KEYS);
        assertThat(profiled.getInteger("docsExamined"))
                .as("1문서보다 많이 읽었다 — 방 전체를 훑고 있다(깔아 둔 수: %d)", OTHER_MESSAGES_IN_ROOM)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ VOD 윈도우 조회가 읽는 문서 수가 창 크기가 아니라 limit 에 묶인다")
    void theVodWindowReadIsBoundedByTheLimit() {
        // given: 창 안에 limit 보다 많이, 창 **뒤**에 훨씬 많이 깔아 둔다(상수 javadoc 참고)
        Instant windowEnd = WINDOW_FROM.plusSeconds(MESSAGES_IN_WINDOW);
        for (int i = 0; i < MESSAGES_IN_WINDOW; i++) {
            repository.save(message("창-" + i, "win-" + i, WINDOW_FROM.plusSeconds(i))).block(VERIFY_CEILING);
        }
        for (int i = 0; i < MESSAGES_AFTER_WINDOW; i++) {
            repository.save(message("창뒤-" + i, "after-" + i, windowEnd.plusSeconds(60L + i)))
                    .block(VERIFY_CEILING);
        }

        // when
        runCommand(new Document("profile", 2)).block(VERIFY_CEILING);
        List<ChatMessage> page = historyRepository.findWindow(
                roomId, WINDOW_FROM, windowEnd, WINDOW_LIMIT, List.of());
        runCommand(new Document("profile", 0)).block(VERIFY_CEILING);

        // then: 분모 먼저 — 연산이 잡혔고 실제로 limit 만큼 돌려줬는가
        Document profiled = lastWindowRead();
        assertThat(profiled)
                .as("프로파일러가 윈도우 조회를 못 잡았다 — 아래 수치의 분모가 0 이다")
                .isNotNull();
        assertThat(page).hasSize(WINDOW_LIMIT);

        assertThat(profiled.getInteger("docsExamined"))
                .as("읽은 문서 수가 limit(%d) 이 아니라 창 전수(%d) 에 묶여 있다 — 비용이 그 방의 "
                        + "5분 생산량에 비례하고 그 엔드포인트는 미인증 공개다. 고른 계획: %s",
                        WINDOW_LIMIT, MESSAGES_IN_WINDOW, profiled.getString("planSummary"))
                .isEqualTo(WINDOW_LIMIT);
    }

    /** {@code system.profile} 에서 윈도우 조회를 찾는다 — {@code createdAt} 범위를 필터에 담은 읽기가 그것뿐이다. */
    private Document lastWindowRead() {
        Document filter = new Document("op", "query")
                .append("command.filter.createdAt", new Document("$exists", true));
        return findProfiled(filter);
    }

    /** {@code system.profile} 에서 재조회 질의를 찾는다 — {@code clientMsgId} 를 필터에 담은 읽기 연산이 그것뿐이다. */
    private Document lastRecoveryRead() {
        Document filter = new Document("op", "query")
                .append("command.filter.clientMsgId", new Document("$exists", true));
        return findProfiled(filter);
    }

    /** 가장 최근에 수집된, 그 필터에 맞는 연산 하나. */
    private Document findProfiled(Document filter) {
        return mongoTemplate
                .execute("system.profile", collection -> Mono.from(collection
                        .find(filter)
                        .sort(new Document("ts", -1))
                        .first()))
                .next()
                .block(VERIFY_CEILING);
    }

    private Mono<Document> runCommand(Document command) {
        return mongoTemplate.execute(database -> Mono.from(database.runCommand(command))).next();
    }

    private ChatMessage message(String text, String clientMsgId) {
        return message(text, clientMsgId, Instant.now());
    }

    private ChatMessage message(String text, String clientMsgId, Instant createdAt) {
        return new ChatMessage(null, roomId, senderId, "구매자닉", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), text, text, clientMsgId, createdAt);
    }
}
