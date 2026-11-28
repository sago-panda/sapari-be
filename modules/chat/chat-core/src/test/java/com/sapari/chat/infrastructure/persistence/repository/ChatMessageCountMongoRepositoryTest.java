package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.bson.UuidRepresentation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * 채팅 수를 실제 Mongo 에서 고정한다 — 공지와 삭제된 메시지는 빼고, 다른 방은 섞지 않는다.
 *
 * <p>삭제 표시는 운영 삭제 어댑터({@code ChatMessageDeletionMongoRepository})로 단다. 손으로 키를 쓰면
 * 키 이름이 어긋나도 이 테스트가 초록이다. UUID 인코딩은 운영과 같게(STANDARD) 맞춘다.
 */
@Testcontainers
@DisplayName("ChatMessageCountRepository — 공지·삭제를 뺀 일반 메시지 수")
class ChatMessageCountMongoRepositoryTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private static MongoTemplate mongoTemplate;
    private static ChatMessageCountMongoRepository repository;
    private static ChatMessageDeletionMongoRepository deletion;

    private static final Instant AT = Instant.parse("2026-11-26T00:00:00Z");

    private final UUID roomId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongo.getConnectionString()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();
        mongoTemplate = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(MongoClients.create(settings), "sapari"));
        repository = new ChatMessageCountMongoRepository(mongoTemplate);
        deletion = new ChatMessageDeletionMongoRepository(mongoTemplate);
    }

    @AfterAll
    static void dropCollection() {
        mongoTemplate.dropCollection(ChatMessageDocument.class);
    }

    private String save(UUID room, String type) {
        return mongoTemplate.save(new ChatMessageDocument(
                null, room, UUID.randomUUID(), "닉", "BUYER", type,
                "원문", "마스킹본", UUID.randomUUID().toString(), AT)).getId();
    }

    private void delete(String id) {
        assertThat(deletion.markDeleted(roomId, id, UUID.randomUUID(), AT)).isTrue();
    }

    @Test
    @DisplayName("⭐ 일반 3 · 공지 2 · 삭제된 일반 1 · 다른 방 일반 4 → 2")
    void countsNormalUndeletedMessagesOfThatRoomOnly() {
        save(roomId, "NORMAL");
        save(roomId, "NORMAL");
        delete(save(roomId, "NORMAL"));
        save(roomId, "NOTICE");
        save(roomId, "NOTICE");
        UUID otherRoom = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            save(otherRoom, "NORMAL");
        }

        assertThat(repository.countMessages(roomId)).isEqualTo(2);
    }

    @Test
    @DisplayName("삭제된 공지는 한 번만 빠진다 — 공지 수와 삭제 수가 겹치지 않는다")
    void aDeletedNoticeIsSubtractedOnce() {
        save(roomId, "NORMAL");
        delete(save(roomId, "NOTICE"));

        assertThat(repository.countMessages(roomId)).isEqualTo(1);
    }

    @Test
    @DisplayName("메시지가 없는 방은 0")
    void anEmptyRoomCountsZero() {
        assertThat(repository.countMessages(UUID.randomUUID())).isZero();
    }
}
