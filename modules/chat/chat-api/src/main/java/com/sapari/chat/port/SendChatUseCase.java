package com.sapari.chat.port;

import reactor.core.publisher.Mono;

import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.view.ChatMessageView;

public interface SendChatUseCase {

    /** 채팅 메시지를 전송한다(권한·욕설필터·저장·브로드캐스트). reactive — streaming-app(WebFlux)에서 호출. */
    Mono<ChatMessageView> send(SendChatCommand command);
}
