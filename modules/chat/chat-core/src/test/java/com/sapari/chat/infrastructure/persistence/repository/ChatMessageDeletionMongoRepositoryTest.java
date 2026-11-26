package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import com.sapari.chat.domain.model.ChatMessageDeletionTarget;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * 삭제 표시를 실제 Mongo 에서 고정한다 — 방 범위, 한 번만 맞는 갱신, 원문 보존.
 *
 * <p>UUID 인코딩을 운영과 같게(STANDARD) 맞춘다. 다르면 {@code roomId} 조건이 다른 바이트를 찾아 모든 갱신이
 * 조용히 0건이 된다. 앱 쪽 배선은 {@code ChatModerationBeansConfig} 가 같은 값을 고정한다.
 */
@Testcontainers
@DisplayName("ChatMessageDeletionRepository — 방 안에서 한 번만 표시하고 원문은 남긴다")
class ChatMessageDeletionMongoRepositoryTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private static MongoTemplate mongoTemplate;
    private static ChatMessageDeletionMongoRepository repository;

    private static final Instant AT = Instant.parse("2026-11-26T00:00:00Z");

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID moderatorId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongo.getConnectionString()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();
        mongoTemplate = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(MongoClients.create(settings), "sapari"));
        repository = new ChatMessageDeletionMongoRepository(mongoTemplate);
    }

    @AfterAll
    static void dropCollection() {
        mongoTemplate.dropCollection(ChatMessageDocument.class);
    }

    private String save(String role) {
        return mongoTemplate.save(new ChatMessageDocument(
                null, roomId, senderId, "닉", role, "NORMAL",
                "원문", "마스킹본", UUID.randomUUID().toString(), AT)).getId();
    }

    private Document raw(String id) {
        return mongoTemplate.findOne(Query.query(Criteria.where("_id").is(new ObjectId(id))),
                Document.class, mongoTemplate.getCollectionName(ChatMessageDocument.class));
    }

    @Test
    @DisplayName("처음 표시하면 true, 같은 메시지를 다시 표시하면 false — 겹친 요청 중 하나만 맞는다")
    void onlyTheFirstMarkWins() {
        String id = save("BUYER");

        assertThat(repository.markDeleted(roomId, id, moderatorId, AT)).isTrue();
        assertThat(repository.markDeleted(roomId, id, UUID.randomUUID(), AT.plusSeconds(1))).isFalse();

        // 두 번째 호출이 표시를 덮어쓰지 않았다
        assertThat(raw(id).get(ChatMessageDocument.DELETED_BY, UUID.class)).isEqualTo(moderatorId);
    }

    @Test
    @DisplayName("⭐ 표시만 달고 원문과 마스킹본은 남긴다")
    void theBodyIsKept() {
        String id = save("BUYER");

        repository.markDeleted(roomId, id, moderatorId, AT);

        Document stored = raw(id);
        assertThat(stored.getString("originalMessage")).isEqualTo("원문");
        assertThat(stored.getString("displayMessage")).isEqualTo("마스킹본");
        assertThat(stored.getDate(ChatMessageDocument.DELETED_AT).toInstant()).isEqualTo(AT);
    }

    @Test
    @DisplayName("⭐ 다른 방의 id 로는 찾지도 표시하지도 못한다")
    void anotherRoomCannotReachTheMessage() {
        String id = save("BUYER");
        UUID otherRoom = UUID.randomUUID();

        assertThat(repository.findInRoom(otherRoom, id)).isEmpty();
        assertThat(repository.markDeleted(otherRoom, id, moderatorId, AT)).isFalse();
        assertThat(raw(id).get(ChatMessageDocument.DELETED_AT)).isNull();
    }

    @Test
    @DisplayName("형식이 어긋난 id 와 없는 id 는 같은 답이다 — 터지지 않는다")
    void malformedAndAbsentIdsAnswerTheSame() {
        assertThat(repository.findInRoom(roomId, "not-an-object-id")).isEmpty();
        assertThat(repository.markDeleted(roomId, "not-an-object-id", moderatorId, AT)).isFalse();
        assertThat(repository.findInRoom(roomId, "000000000000000000000000")).isEmpty();
        assertThat(repository.markDeleted(roomId, "000000000000000000000000", moderatorId, AT)).isFalse();
    }

    @Test
    @DisplayName("대상 조각은 발신자·역할·표시 여부를 싣는다")
    void theTargetCarriesSenderRoleAndDeletedFlag() {
        String id = save("ADMIN");

        assertThat(repository.findInRoom(roomId, id))
                .contains(new ChatMessageDeletionTarget(senderId, ChatRole.ADMIN, false));

        repository.markDeleted(roomId, id, moderatorId, AT);

        assertThat(repository.findInRoom(roomId, id))
                .contains(new ChatMessageDeletionTarget(senderId, ChatRole.ADMIN, true));
    }
}
