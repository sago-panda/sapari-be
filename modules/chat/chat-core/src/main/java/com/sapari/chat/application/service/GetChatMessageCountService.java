package com.sapari.chat.application.service;

import java.util.UUID;

import org.springframework.dao.DataAccessException;

import com.sapari.chat.domain.repository.ChatMessageCountRepository;
import com.sapari.chat.exception.ChatMessageCountUnavailableException;
import com.sapari.chat.port.GetChatMessageCountUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 방 하나의 채팅 수 — 저장소 장애를 호출 도메인이 아는 예외로 번역한다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 저장소에 기대므로 live-app 이 명시로 등록한다.
 */
@RequiredArgsConstructor
public class GetChatMessageCountService implements GetChatMessageCountUseCase {

    private final ChatMessageCountRepository countRepository;

    @Override
    public long countMessages(UUID roomId) {
        try {
            return countRepository.countMessages(roomId);
        } catch (DataAccessException e) {
            throw new ChatMessageCountUnavailableException(e);
        }
    }
}
