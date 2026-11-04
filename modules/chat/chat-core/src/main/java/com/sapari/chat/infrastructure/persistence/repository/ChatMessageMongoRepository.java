package com.sapari.chat.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import com.sapari.chat.infrastructure.persistence.document.ChatMessageDocument;

import reactor.core.publisher.Mono;

public interface ChatMessageMongoRepository extends ReactiveMongoRepository<ChatMessageDocument, String> {

    // DuplicateKey 시 기존 메시지 재조회용 — (roomId, senderId, clientMsgId) unique partial 인덱스와 동일 키
    Mono<ChatMessageDocument> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId);
}
