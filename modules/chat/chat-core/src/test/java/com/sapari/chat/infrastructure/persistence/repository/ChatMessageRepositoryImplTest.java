package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.infrastructure.config.ChatMongoConfig;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import reactor.test.StepVerifier;

/**
 * 실제 MongoDB(TestContainers)로 저장·dedup·역순 페이징을 검증한다.
 * TC 번호는 설계 문서 검증 계획의 GetChatHistoryService 표 중 repository 책임 항목을 따른다.
 * (Boot 4는 @DataMongoTest 슬라이스가 없어 @SpringBootTest 사용 — chat-core 컨텍스트는 어댑터뿐이라 작다)
 */
// UUID 인코딩은 ChatMongoConfig의 MongoClientSettingsBuilderCustomizer가 STANDARD로 고정.
// DataSource 자동설정은 끈다 — 강퇴 로그 때문에 모듈에 JPA가 들어오면서 이 Mongo 테스트까지 관계형 DB를
// 요구하게 됐다. 여기는 Postgres를 쓰지 않으므로 컨테이너를 늘리는 대신 자동설정을 잘라낸다.
// (Hibernate·트랜잭션 자동설정은 DataSource 빈에 조건이 걸려 있어 함께 꺼진다)
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Testcontainers
class ChatMessageRepositoryImplTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    /**
     * 이 테스트가 Redis를 쓰지는 않는다. 그런데 부트 컨텍스트에 {@code RedisChatBroadcaster}가 함께 올라오고,
     * 그 어댑터는 <b>생성자에서 곧장 구독을 연다</b>({@code autoConnect(0)}) — 구독 활성 전 메시지를 흘리지
     * 않으려는 설계라, 컨테이너가 없으면 빈 생성 단계에서 접속 실패로 컨텍스트 전체가 못 뜬다.
     *
     * <p>어댑터를 늦게 붙게 고치는 쪽이 아니라 컨테이너를 하나 더 띄우는 쪽을 택한다. 즉시 연결이
     * 그 어댑터가 유실 레이스를 없앤 방법 자체라, 테스트 편의로 되돌리면 운영에서 잃는 것이 생긴다.
     *
     * <p>{@code @ServiceConnection}에 이름을 준 이유: 이미지 이름만으로 종류를 알아내는 대상이
     * {@code GenericContainer}에는 없다.
     */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Autowired
    private ChatMessageRepositoryImpl repository;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 컬렉션 drop은 인덱스도 지우므로 매번 재생성 (멱등)
        mongoTemplate.dropCollection(ChatMessageDocument.class).block(VERIFY_CEILING);
        ChatMongoConfig.createChatMessagesIndexes(mongoTemplate);
    }

    @Test
    @DisplayName("save — id가 채번되고 전 필드가 보존된다")
    void save_assigns_id_and_preserves_fields() {
        // given
        ChatMessage saved = repository.save(message("안녕하세요", "client-1")).block(VERIFY_CEILING);

        // when & then
        assertThat(saved.id()).isNotNull();
        assertThat(saved.displayMessage()).isEqualTo("안녕하세요");
        assertThat(saved.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(saved.type()).isInstanceOf(ChatMessageType.Normal.class);
    }

    @Test
    @DisplayName("TC#15 — 다른 방 메시지는 섞이지 않는다 (roomId 격리)")
    void other_room_messages_are_isolated() {
        // given
        repository.save(message("mine", null)).block(VERIFY_CEILING);
        ChatMessage other = new ChatMessage(null, UUID.randomUUID(), senderId, "닉", "e@x.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), "남의방", "남의방", null, Instant.now());
        repository.save(other).block(VERIFY_CEILING);

        // 읽기는 템플릿으로 한다 — 이 테스트가 재는 것은 <b>쓰기가 roomId 를 제대로 담는가</b>이고,
        // 읽는 쪽 격리는 이력 어댑터 테스트가 따로 잰다. 종전에는 리액티브 커서 조회로 읽었는데
        // 그 메서드가 운영 호출자 없이 지워졌다.
        List<ChatMessageDocument> stored = mongoTemplate
                .find(Query.query(Criteria.where("roomId").is(roomId)), ChatMessageDocument.class)
                .collectList().block(VERIFY_CEILING);

        // when & then
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getDisplayMessage()).isEqualTo("mine");
    }

    @Test
    @DisplayName("dedup — 같은 (roomId, senderId, clientMsgId) 재삽입은 DuplicateKey, 재조회로 기존 메시지 복구")
    void duplicate_clientMsgId_is_rejected_and_recoverable() {
        // given
        ChatMessage first = repository.save(message("원본", "dup-key")).block(VERIFY_CEILING);

        // when
        assertThatThrownBy(() -> repository.save(message("중복", "dup-key")).block(VERIFY_CEILING))
                .isInstanceOf(DuplicateKeyException.class);

        // then
        ChatMessage recovered = repository
                .findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "dup-key").block(VERIFY_CEILING);
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.displayMessage()).isEqualTo("원본");
    }

    @Test
    @DisplayName("dedup — clientMsgId=null은 partial 인덱스 미적용이라 2건 이상 저장 가능")
    void null_clientMsgId_is_not_deduplicated() {
        // given
        repository.save(message("첫번째", null)).block(VERIFY_CEILING);
        repository.save(message("두번째", null)).block(VERIFY_CEILING);

        // when & then: 저장된 문서 수로 본다(종전에는 지워진 커서 조회로 읽었다)
        assertThat(mongoTemplate
                .count(Query.query(Criteria.where("roomId").is(roomId)), ChatMessageDocument.class)
                .block(VERIFY_CEILING)).isEqualTo(2);
    }

    @Test
    @DisplayName("인덱스 4종(페이징·dedup unique partial·TTL 2년·VOD 윈도우)이 생성된다")
    void creates_four_indexes() {
        // given
        var indexes = mongoTemplate.indexOps(ChatMessageDocument.class).getIndexInfo().collectList().block(VERIFY_CEILING);

        // when & then: 넷을 다 적는다 — 종전에는 셋만 적어 윈도우 인덱스의 이름이 아무 데도
        // 단언되지 않았고, 그래서 그 인덱스의 키가 바뀌어도 이 칸이 초록이었다.
        assertThat(indexes).extracting(i -> i.getName())
                .contains("roomId_1__id_1", "roomId_1_senderId_1_clientMsgId_1", "createdAt_1",
                        "roomId_1_createdAt_1__id_1");
        var ttl = indexes.stream().filter(i -> i.getName().equals("createdAt_1")).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).isPresent();
        assertThat(ttl.getExpireAfter().get().toDays()).isEqualTo(730);
    }

    @Test
    @DisplayName("⭐ 옛 2키 윈도우 인덱스는 걷어낸다 — 그냥 두면 가장 뜨거운 쓰기 경로에 인덱스가 하나 늘어난다")
    void the_superseded_two_key_window_index_is_removed() {
        // given: 이미 배포된 환경의 모양을 만든다. 3키는 2키의 **접두가 아니라 상위집합**이라
        // 이름이 달라지고, 그러면 Mongo 는 기존 것을 두고 새 것을 하나 더 만든다
        // (실측, 일회용 mongo:7.0: "_id_ | roomId_1_createdAt_1 | roomId_1_createdAt_1__id_1").
        // 그 상태는 조회에 해가 없지만 chat_messages 의 모든 insert 가 인덱스를 하나 더 쓴다.
        mongoTemplate.indexOps(ChatMessageDocument.class).createIndex(new Index()
                .on("roomId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)).block(VERIFY_CEILING);

        // when: 부팅 경로를 그 상태 위에서 돌린다
        ChatMongoConfig.createChatMessagesIndexes(mongoTemplate);

        // then
        assertThat(mongoTemplate.indexOps(ChatMessageDocument.class).getIndexInfo()
                .map(i -> i.getName()).collectList().block(VERIFY_CEILING))
                .as("옛 2키 윈도우 인덱스가 남아 있다 — 상위집합이 섰는데도 쓰기 비용을 계속 낸다")
                .doesNotContain("roomId_1_createdAt_1")
                .contains("roomId_1_createdAt_1__id_1");
    }

    private ChatMessage message(String text, String clientMsgId) {
        return new ChatMessage(null, roomId, senderId, "구매자닉", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), text, text, clientMsgId, Instant.now());
    }
}
