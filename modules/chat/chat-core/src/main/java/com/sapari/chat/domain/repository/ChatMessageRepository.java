package com.sapari.chat.domain.repository;

import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 채팅 메시지 영속 포트 (reactive — streaming-app 이벤트루프에서 호출).
 * 이력 조회(강퇴자 제외·VOD 창)는 블로킹이라 {@code ChatHistoryRepository} 가 따로 맡는다.
 */
public interface ChatMessageRepository {

    Mono<ChatMessage> save(ChatMessage message);

    // 중복 전송(DuplicateKey) 발생 시 기존 메시지 재조회용
    Mono<ChatMessage> findByRoomIdAndSenderIdAndClientMsgId(UUID roomId, UUID senderId, String clientMsgId);

    // ⚠️ 운영 호출자가 없다 — 이력 조회는 블로킹 포트(ChatHistoryRepository)로 갔다. 호출자가 MVC 라
    // Mono 를 받으면 결국 block() 을 하게 되기 때문이다. 정리 대상이며 구현 쪽 주석에 사유를 적었다.
    // beforeId 이전(_id 내림차순) size건. size > 0 불변식(0 이하는 IllegalArgumentException).
    Flux<ChatMessage> findByRoomIdBefore(UUID roomId, String beforeId, int size);
}
