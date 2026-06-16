package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.repository.ChatMessageRepository;
import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;
import com.sapari.chat.infrastructure.persistence.mapper.ChatMessageMapper;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepository {

    private final ChatMessageMongoRepository mongoRepository;
    private final ReactiveMongoTemplate mongoTemplate;
    private final ChatMessageMapper mapper;

    @Override
    public Mono<ChatMessage> save(ChatMessage message) {
        return mongoRepository.save(mapper.toDocument(message))
                .map(mapper::toDomain);
    }

    @Override
    public Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId) {
        return mongoRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, clientMsgId)
                .map(mapper::toDomain);
    }

    @Override
    public Flux<ChatMessage> findByRoomIdBefore(UUID roomId, String beforeId, int size) {
        // Mongo limit(0) = 무제한 — size<=0이 들어오면 방 전체를 이벤트루프로 긁어오므로 loud failure로 차단
        if (size <= 0) {
            return Flux.error(new IllegalArgumentException("size는 1 이상이어야 한다: " + size));
        }
        Criteria criteria = Criteria.where("roomId").is(roomId);
        if (beforeId != null) {
            if (!ObjectId.isValid(beforeId)) {
                return Flux.error(new IllegalArgumentException("잘못된 ObjectId 형식: " + beforeId));
            }
            criteria = criteria.and("_id").lt(new ObjectId(beforeId));
        }
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .limit(size);
        return mongoTemplate.find(query, ChatMessageDocument.class)
                .map(mapper::toDomain);
    }
}
