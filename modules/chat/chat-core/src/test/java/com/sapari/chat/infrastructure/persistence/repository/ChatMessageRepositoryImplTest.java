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
 * TC 번호는 GetChatHistoryService 표(§12.1) 중 repository 책임 항목.
 * (Boot 4는 @DataMongoTest 슬라이스가 없어 @SpringBootTest 사용 — chat-core 컨텍스트는 어댑터뿐이라 작다)
 */
@SpringBootTest  // UUID 인코딩은 ChatMongoConfig의 MongoClientSettingsBuilderCustomizer가 STANDARD로 고정
@Testcontainers
class ChatMessageRepositoryImplTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

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
        ChatMessage saved = repository.save(message("안녕하세요", "client-1")).block();

        assertThat(saved.id()).isNotNull();
        assertThat(saved.displayMessage()).isEqualTo("안녕하세요");
        assertThat(saved.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(saved.type()).isInstanceOf(ChatMessageType.Normal.class);
    }

    @Test
    @DisplayName("TC#1 — beforeId 없이 size=5 조회 시 최신 5개를 ObjectId 역순으로 반환한다")
    void returns_latest_messages_in_reverse_order() {
        for (int i = 0; i < 10; i++) {
            repository.save(message("m" + i, null)).block();
        }

        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, null, 5).collectList().block();

        assertThat(page).hasSize(5);
        assertThat(page.get(0).displayMessage()).isEqualTo("m9"); // 최신부터
        assertThat(page.get(4).displayMessage()).isEqualTo("m5");
    }

    @Test
    @DisplayName("TC#2·#3 — 1페이지 마지막 _id를 beforeId로 2페이지 조회 시 중복 없이 연속된다")
    void cursor_paging_is_continuous_without_duplicates() {
        for (int i = 0; i < 10; i++) {
            repository.save(message("m" + i, null)).block();
        }
        List<ChatMessage> page1 = repository.findByRoomIdBefore(roomId, null, 5).collectList().block();

        List<ChatMessage> page2 = repository
                .findByRoomIdBefore(roomId, page1.get(4).id(), 5).collectList().block();

        assertThat(page2).hasSize(5);
        assertThat(page2.get(0).displayMessage()).isEqualTo("m4"); // page1 마지막(m5) 직전부터
        assertThat(page2).extracting(ChatMessage::id).doesNotContainAnyElementsOf(
                page1.stream().map(ChatMessage::id).toList());
    }

    @Test
    @DisplayName("TC#8·#19 — 메시지 없는 방 조회 시 빈 목록, 예외 없음")
    void empty_room_returns_empty_list() {
        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, null, 5).collectList().block();

        assertThat(page).isEmpty();
    }

    @Test
    @DisplayName("TC#25 — 형식은 유효하나 모든 메시지보다 오래된 beforeId면 빈 목록")
    void beforeId_older_than_all_returns_empty() {
        repository.save(message("m0", null)).block();
        String epochId = new ObjectId(new Date(0)).toHexString();

        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, epochId, 5).collectList().block();

        assertThat(page).isEmpty();
    }

    @Test
    @DisplayName("TC#14 — 잘못된 ObjectId 형식의 beforeId는 IllegalArgumentException")
    void invalid_beforeId_format_fails() {
        StepVerifier.create(repository.findByRoomIdBefore(roomId, "not-an-objectid", 5))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("size<=0은 IllegalArgumentException — Mongo limit(0) 무제한 전량조회 차단")
    void non_positive_size_fails() {
        StepVerifier.create(repository.findByRoomIdBefore(roomId, null, 0))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("TC#15 — 다른 방 메시지는 섞이지 않는다 (roomId 격리)")
    void other_room_messages_are_isolated() {
        repository.save(message("mine", null)).block();
        ChatMessage other = new ChatMessage(null, UUID.randomUUID(), senderId, "닉", "e@x.com",
                ChatRole.BUYER, new ChatMessageType.Normal(), "남의방", "남의방", null, Instant.now());
        repository.save(other).block();

        List<ChatMessage> page = repository.findByRoomIdBefore(roomId, null, 10).collectList().block();

        assertThat(page).hasSize(1);
        assertThat(page.get(0).displayMessage()).isEqualTo("mine");
    }

    @Test
    @DisplayName("dedup — 같은 (roomId, senderId, clientMsgId) 재삽입은 DuplicateKey, 재조회로 기존 메시지 복구")
    void duplicate_clientMsgId_is_rejected_and_recoverable() {
        ChatMessage first = repository.save(message("원본", "dup-key")).block();

        assertThatThrownBy(() -> repository.save(message("중복", "dup-key")).block())
                .isInstanceOf(DuplicateKeyException.class);

        ChatMessage recovered = repository
                .findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, "dup-key").block();
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.displayMessage()).isEqualTo("원본");
    }

    @Test
    @DisplayName("dedup — clientMsgId=null은 partial 인덱스 미적용이라 2건 이상 저장 가능")
    void null_clientMsgId_is_not_deduplicated() {
        repository.save(message("첫번째", null)).block();
        repository.save(message("두번째", null)).block();

        assertThat(repository.findByRoomIdBefore(roomId, null, 10).collectList().block()).hasSize(2);
    }

    @Test
    @DisplayName("인덱스 3종(페이징·dedup unique partial·TTL 2년)이 생성된다")
    void creates_three_indexes() {
        var indexes = mongoTemplate.indexOps(ChatMessageDocument.class).getIndexInfo().collectList().block();

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
