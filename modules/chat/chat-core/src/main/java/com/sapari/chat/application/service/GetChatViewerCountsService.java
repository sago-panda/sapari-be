package com.sapari.chat.application.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;

import com.sapari.chat.domain.repository.ChatViewerCountRepository;
import com.sapari.chat.exception.ChatViewerCountUnavailableException;
import com.sapari.chat.port.GetChatViewerCountsUseCase;

import lombok.RequiredArgsConstructor;

/**
 * 방별 고유 시청자 수 — 저장소 장애를 호출 도메인이 아는 예외로 번역한다.
 *
 * <p>스테레오타입을 붙이지 않는다 — 블로킹 저장소에 기대므로 live-app 이 명시로 등록한다.
 */
@RequiredArgsConstructor
public class GetChatViewerCountsService implements GetChatViewerCountsUseCase {

    private final ChatViewerCountRepository viewerCountRepository;

    @Override
    public Map<UUID, Long> countViewers(Collection<UUID> roomIds) {
        try {
            return viewerCountRepository.countViewers(roomIds);
        } catch (DataAccessException e) {
            throw new ChatViewerCountUnavailableException(e);
        }
    }
}
