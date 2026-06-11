package com.sapari.chat.application.port;

import java.util.UUID;

import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.domain.model.ChatMessage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pod 간 채팅 중계 — chat:pubsub:{roomId} 발행/구독.
 * KICK_EVENT 발행은 api-app(KickUserService)이 직접 수행하므로 여기 두지 않는다.
 */
public interface ChatBroadcaster {

    Mono<Void> publish(UUID roomId, ChatMessage message);  // CHAT 봉투 발행

    Flux<ChatEnvelope> subscribe(UUID roomId);
}
