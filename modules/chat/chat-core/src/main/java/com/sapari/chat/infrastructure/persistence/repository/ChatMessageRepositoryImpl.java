package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.sapari.chat.domain.exception.ChatPersistTimeoutException;
import com.sapari.chat.infrastructure.persistence.MongoOperationTimeout;
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
                .map(mapper::toDomain)
                // 연산 상한이 끊은 것을 도메인 타입으로 바꿔 올린다. 번역이 이 층인 이유가 둘이다 —
                // 루트 AGENTS.md 가 서비스의 SDK 직접 사용을 금지하고, ArchUnit 의
                // application_must_not_depend_on_infrastructure 가 그 반대 방향도 막는다.
                .onErrorMap(MongoOperationTimeout::matches, ChatPersistTimeoutException::new);
    }

    @Override
    public Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId) {
        return mongoRepository.findByRoomIdAndSenderIdAndClientMsgId(roomId, senderId, clientMsgId)
                .map(mapper::toDomain);
    }
}
