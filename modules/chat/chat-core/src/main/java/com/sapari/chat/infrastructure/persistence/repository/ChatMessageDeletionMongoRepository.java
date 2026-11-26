package com.sapari.chat.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.sapari.chat.domain.model.ChatMessageDeletionTarget;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatMessageDeletionRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import lombok.RequiredArgsConstructor;

/**
 * {@code chat_messages} 문서에 삭제 표시({@code deletedAt}·{@code deletedBy})를 {@code $set} 으로 다는 블로킹 어댑터.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 어댑터는 그 스택을 가진 앱이 명시로 등록한다(증거 조회와 동일).
 */
@RequiredArgsConstructor
public class ChatMessageDeletionMongoRepository implements ChatMessageDeletionRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public Optional<ChatMessageDeletionTarget> findInRoom(UUID roomId, String messageId) {
        if (!ObjectId.isValid(messageId)) {
            return Optional.empty();
        }
        Query query = inRoom(roomId, messageId);
        query.fields().include("senderId", "senderRole", ChatMessageDocument.DELETED_AT);
        // 도큐먼트 클래스로 읽지 않는다 — 삭제 표시는 그 클래스의 필드가 아니라서 읽히지 않는다.
        return Optional.ofNullable(mongoTemplate.findOne(query, Document.class, collection()))
                .map(found -> new ChatMessageDeletionTarget(
                        found.get("senderId", UUID.class),
                        senderRole(found.getString("senderRole"), messageId),
                        found.get(ChatMessageDocument.DELETED_AT) != null));
    }

    @Override
    public boolean markDeleted(UUID roomId, String messageId, UUID deletedBy, Instant deletedAt) {
        if (!ObjectId.isValid(messageId)) {
            return false;
        }
        // 조건에 "아직 표시 없음" 을 넣어 갱신이 한 번만 맞게 한다. 겹친 두 요청 중 하나만 true 를 받는다.
        Query query = inRoom(roomId, messageId);
        query.addCriteria(Criteria.where(ChatMessageDocument.DELETED_AT).is(null));
        Update update = new Update()
                .set(ChatMessageDocument.DELETED_AT, deletedAt)
                .set(ChatMessageDocument.DELETED_BY, deletedBy);
        return mongoTemplate.updateFirst(query, update, collection()).getModifiedCount() == 1;
    }

    private static Query inRoom(UUID roomId, String messageId) {
        return Query.query(Criteria.where("_id").is(new ObjectId(messageId)).and("roomId").is(roomId));
    }

    private String collection() {
        return mongoTemplate.getCollectionName(ChatMessageDocument.class);
    }

    /** 모르는 값은 요청이 아니라 저장 데이터의 잘못이다 — 증거 조회({@code ChatMessageEvidenceMongoRepository})와 같은 판단. */
    private static ChatRole senderRole(String stored, String messageId) {
        try {
            return ChatRole.valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("삭제 대상 메시지의 역할 값을 해석할 수 없다 — messageId=" + messageId, e);
        }
    }
}
