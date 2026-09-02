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
import com.sapari.chat.domain.model.ChatMessageEvidence;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

/**
 * 강퇴 증거 조회를 실제 Mongo에서 고정한다.
 *
 * <p>확인하는 것은 셋이다 — <b>원문</b>(마스킹본이 아니라)을 가져오는가, <b>없는 id</b>에 빈 값으로
 * 답하는가, <b>형식이 어긋난 id</b>에 터지지 않고 빈 값으로 답하는가. 셋 다 목으로는 검증되지 않는다:
 * 앞의 둘은 실제 문서 매핑이고, 마지막은 드라이버가 잘못된 id에 어떻게 반응하는지다.
 *
 * <p>UUID 인코딩을 운영과 같게(STANDARD) 맞춘다 — 다르면 {@code roomId}·{@code senderId}가 다른 바이트로
 * 저장돼 정합 검증이 이유 없이 어긋난다. 운영에서는 {@code ChatMongoConfig}가 같은 값을 고정한다.
 */
@Testcontainers
@DisplayName("ChatMessageEvidenceRepository — 증거는 마스킹 전 원문이다")
class ChatMessageEvidenceMongoRepositoryTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    private static MongoTemplate mongoTemplate;
    private static ChatMessageEvidenceMongoRepository repository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongo.getConnectionString()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();
        mongoTemplate = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(MongoClients.create(settings), "sapari"));
        repository = new ChatMessageEvidenceMongoRepository(mongoTemplate);
    }

    @AfterAll
    static void dropCollection() {
        mongoTemplate.dropCollection(ChatMessageDocument.class);
    }

    private ChatMessageDocument save(String original, String display) {
        return mongoTemplate.save(new ChatMessageDocument(
                null, roomId, senderId, "구매자닉", "buyer@example.com", "BUYER", "NORMAL",
                original, display, UUID.randomUUID().toString(), Instant.parse("2026-09-02T00:00:00Z")));
    }

    @Test
    @DisplayName("마스킹본이 아니라 원문을 가져온다 — 가려진 본문은 증거가 되지 못한다")
    void readsOriginalMessageNotMasked() {
        // given
        ChatMessageDocument saved = save("문제된 원문", "문제된 ***");

        // when
        ChatMessageEvidence evidence = repository.findEvidence(saved.getId()).orElseThrow();

        // then
        assertThat(evidence.originalMessage()).isEqualTo("문제된 원문");
        assertThat(evidence.roomId()).isEqualTo(roomId);
        assertThat(evidence.senderId()).isEqualTo(senderId);
    }

    @Test
    @DisplayName("없는 메시지는 빈 값 — 호출자는 강퇴를 거부한다")
    void missingMessageIsEmpty() {
        // when & then: 형식은 맞지만 존재하지 않는 id
        assertThat(repository.findEvidence("000000000000000000000000")).isEmpty();
    }

    @Test
    @DisplayName("형식이 어긋난 id도 터지지 않고 빈 값 — 형식 여부를 응답으로 알려주지 않는다")
    void malformedIdIsEmptyNotAnError() {
        // when & then: 던져서 갈라 보여 주면 id를 더듬는 쪽에 탐색 신호가 된다
        assertThat(repository.findEvidence("not-an-objectid")).isEmpty();
    }
}
