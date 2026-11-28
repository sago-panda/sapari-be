package com.sapari.chat.domain.repository;

import java.util.UUID;

import com.sapari.chat.port.BlockingChat;

/** 방 하나의 채팅 수(블로킹). 세는 규칙은 {@code GetChatMessageCountUseCase} 에 적혀 있다. */
@BlockingChat
public interface ChatMessageCountRepository {

    long countMessages(UUID roomId);
}
