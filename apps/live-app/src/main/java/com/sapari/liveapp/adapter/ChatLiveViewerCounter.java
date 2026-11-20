package com.sapari.liveapp.adapter;

import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.exception.ChatViewerCountUnavailableException;
import com.sapari.chat.port.GetChatViewerCountsUseCase;
import com.sapari.live.application.port.LiveViewerCounter;
import com.sapari.live.application.port.ViewerCountUnavailableException;

/**
 * live 의 시청자 수 포트를 chat-api 로 잇는다. 두 도메인을 모두 아는 곳이 앱뿐이라 여기 둔다
 * ({@link LiveViewerCounter} javadoc 의 순환 참고). 실패는 live 쪽 예외로 번역만 한다.
 */
@Component
@RequiredArgsConstructor
public class ChatLiveViewerCounter implements LiveViewerCounter {

    private final GetChatViewerCountsUseCase getChatViewerCountsUseCase;

    @Override
    public Map<UUID, Long> countViewers(Collection<UUID> roomIds) {
        try {
            return getChatViewerCountsUseCase.countViewers(roomIds);
        } catch (ChatViewerCountUnavailableException e) {
            throw new ViewerCountUnavailableException(e);
        }
    }
}
