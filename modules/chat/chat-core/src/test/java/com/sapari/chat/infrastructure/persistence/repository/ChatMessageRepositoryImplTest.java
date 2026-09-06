package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
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

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

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
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired
    private ChatMessageRepositoryImpl repository;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 컬렉션 drop은 인덱스도 지우므로 매번 재생성 (멱등)
        mongoTemplate.dropCollection(ChatMessageDocument.class).block();
        ChatMongoConfig.createChatMessagesIndexes(mongoTemplate);
    }

    @Test
    @DisplayName("save — id가 채번되고 전 필드가 보존된다")
    void save_assigns_id_and_preserves_fields() {
        // given
        ChatMessage saved = repository.save(message("안녕하세요", "client-1")).block();

        // when & then
        assertThat(saved.id()).isNotNull();
        assertThat(saved.displayMessage()).isEqualTo("안녕하세요");
        assertThat(saved.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(saved.type()).isInstanceOf(ChatMessageType.Normal.class);
    }

    @Test
    @DisplayName("TC#1 — beforeId 없이 size=5 조회 시 최신 5개를 ObjectId 역순으로 반환한다")
    void returns_latest_messages_in_reverse_order() {
        // given
        for (int i = 0; i < 10; i++) {
            repository.save(message("m" + i, null)).block();
        }

        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, null, 5).collectList().block();

        // when & then
        assertThat(page).hasSize(5);
        assertThat(page.get(0).displayMessage()).isEqualTo("m9"); // 최신부터
        assertThat(page.get(4).displayMessage()).isEqualTo("m5");
    }

    @Test
    @DisplayName("TC#2·#3 — 1페이지 마지막 _id를 beforeId로 2페이지 조회 시 중복 없이 연속된다")
    void cursor_paging_is_continuous_without_duplicates() {
        // given
        for (int i = 0; i < 10; i++) {
            repository.save(message("m" + i, null)).block();
        }
        List<ChatMessage> page1 = repository.findByRoomIdBefore(roomId, null, 5).collectList().block();

        List<ChatMessage> page2 = repository
                .findByRoomIdBefore(roomId, page1.get(4).id(), 5).collectList().block();

        // when & then
        assertThat(page2).hasSize(5);
        assertThat(page2.get(0).displayMessage()).isEqualTo("m4"); // page1 마지막(m5) 직전부터
        assertThat(page2).extracting(ChatMessage::id).doesNotContainAnyElementsOf(
                page1.stream().map(ChatMessage::id).toList());
    }

    @Test
    @DisplayName("TC#8·#19 — 메시지 없는 방 조회 시 빈 목록, 예외 없음")
    void empty_room_returns_empty_list() {
        // given
        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, null, 5).collectList().block();

        // when & then
        assertThat(page).isEmpty();
    }

    @Test
    @DisplayName("TC#25 — 형식은 유효하나 모든 메시지보다 오래된 beforeId면 빈 목록")
    void beforeId_older_than_all_returns_empty() {
        // given
        repository.save(message("m0", null)).block();
        String epochId = new ObjectId(new Date(0)).toHexString();

        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, epochId, 5).collectList().block();

        // when & then
        assertThat(page).isEmpty();
    }

    @Test
    @DisplayName("TC#14 — 잘못된 ObjectId 형식의 beforeId는 IllegalArgumentException")
    void invalid_beforeId_format_fails() {
        // when & then
        StepVerifier.create(repository.findByRoomIdBefore(roomId, "not-an-objectid", 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("size<=0은 IllegalArgumentException — Mongo limit(0) 무제한 전량조회 차단")
    void non_positive_size_fails() {
        // when & then
        StepVerifier.create(repository.findByRoomIdBefore(roomId, null, 0))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("TC#15 — 다른 방 메시지는 섞이지 않는다 (roomId 격리)")
    void other_room_messages_are_isolated() {
        // given
        repository.save(message("mine", null)).block();
        ChatMessage other = new ChatMessage(null, UUID.randomUUID(), senderId, "닉", "e@x.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), "남의방", "남의방", null, Instant.now());
        repository.save(other).block();

        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, null, 10).collectList().block();

        // when & then
        assertThat(page).hasSize(1);
        assertThat(page.get(0).displayMessage()).isEqualTo("mine");
    }

    @Test
    @DisplayName("dedup — 같은 (roomId, senderId, clientMsgId) 재삽입은 DuplicateKey, 재조회로 기존 메시지 복구")
    void duplicate_clientMsgId_is_rejected_and_recoverable() {
        // given
        ChatMessage first = repository.save(message("원본", "dup-key")).block();

        // when
        assertThatThrownBy(() -> repository.save(message("중복", "dup-key")).block())
                .isInstanceOf(DuplicateKeyException.class);

        // then
        ChatMessage recovered = repository
                .findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "dup-key").block();
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.displayMessage()).isEqualTo("원본");
    }

    @Test
    @DisplayName("dedup — clientMsgId=null은 partial 인덱스 미적용이라 2건 이상 저장 가능")
    void null_clientMsgId_is_not_deduplicated() {
        // given
        repository.save(message("첫번째", null)).block();
        repository.save(message("두번째", null)).block();

        // when & then
        assertThat(repository.findByRoomIdBefore(roomId, null, 10).collectList().block()).hasSize(2);
    }

    @Test
    @DisplayName("인덱스 3종(페이징·dedup unique partial·TTL 2년)이 생성된다")
    void creates_three_indexes() {
        // given
        var indexes = mongoTemplate.indexOps(ChatMessageDocument.class).getIndexInfo().collectList().block();

        // when & then
        assertThat(indexes).extracting(i -> i.getName())
                .contains("roomId_1__id_1", "roomId_1_senderId_1_clientMsgId_1", "createdAt_1");
        var ttl = indexes.stream().filter(i -> i.getName().equals("createdAt_1")).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).isPresent();
        assertThat(ttl.getExpireAfter().get().toDays()).isEqualTo(730);
    }

    private ChatMessage message(String text, String clientMsgId) {
        return new ChatMessage(null, roomId, senderId, "구매자닉", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), text, text, clientMsgId, Instant.now());
    }
}
