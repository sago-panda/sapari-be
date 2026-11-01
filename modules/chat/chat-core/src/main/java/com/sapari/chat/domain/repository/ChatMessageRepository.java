package com.sapari.chat.domain.repository;

import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessage;

import reactor.core.publisher.Mono;

/**
 * 채팅 메시지 영속 포트 (reactive — streaming-app 이벤트루프에서 호출).
 * 이력 조회(강퇴자 제외·VOD 창)는 블로킹이라 {@code ChatHistoryRepository} 가 따로 맡는다.
 */
public interface ChatMessageRepository {

    Mono<ChatMessage> save(ChatMessage message);

    // 중복 전송(DuplicateKey) 발생 시 기존 메시지 재조회용
    Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId);

}
