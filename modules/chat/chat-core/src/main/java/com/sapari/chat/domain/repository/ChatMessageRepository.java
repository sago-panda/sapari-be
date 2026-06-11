package com.sapari.chat.domain.repository;

import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 채팅 메시지 영속 포트 (reactive — streaming-app 이벤트루프에서 호출).
 * VOD 강퇴자 제외 조회는 api-app(blocking) 책임이라 여기 두지 않는다.
 */
public interface ChatMessageRepository {

    Mono<ChatMessage> save(ChatMessage message);

    // 중복 전송(DuplicateKey) 발생 시 기존 메시지 재조회용
    Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId);

    // 이력 역순 페이징 — beforeId 이전(_id 기준 내림차순) size건
    Flux<ChatMessage> findByRoomIdBefore(UUID roomId, String beforeId, int size);
}
