package com.sapari.chat.application.port;

import java.util.UUID;

import reactor.core.publisher.Flux;

/**
 * live가 발행하는 방 종료 이벤트 스트림 — {@code live:room:ended} Redis Pub/Sub 채널(live 소유 계약).
 *
 * <p>payload는 JSON {@code {"roomId":"<uuid>","endedAt":"<iso8601>"}}. chat이 실제 쓰는 건 roomId뿐이라
 * 어댑터가 roomId만 뽑아 흘려보낸다. {@code ChatBroadcaster}(chat:pubsub)와는 다른 채널·목적이라 분리한다.
 */
public interface LiveRoomEndedSource {

    Flux<UUID> ended();
}
