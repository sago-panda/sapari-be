package com.sapari.liveapp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.exception.ChatViewerCountUnavailableException;
import com.sapari.chat.port.GetChatViewerCountsUseCase;
import com.sapari.live.application.port.ViewerCountUnavailableException;

class ChatLiveViewerCounterTest {

    private final GetChatViewerCountsUseCase chat = mock(GetChatViewerCountsUseCase.class);
    private final ChatLiveViewerCounter counter = new ChatLiveViewerCounter(chat);

    @Test
    @DisplayName("채팅 시청자 수를 그대로 넘긴다")
    void delegates() {
        UUID room = UUID.randomUUID();
        given(chat.countViewers(List.of(room))).willReturn(Map.of(room, 3L));

        assertThat(counter.countViewers(List.of(room))).containsExactly(Map.entry(room, 3L));
    }

    @Test
    @DisplayName("채팅 장애는 live 쪽 예외로 번역한다 — 원인 보존")
    void translatesFailure() {
        ChatViewerCountUnavailableException cause = new ChatViewerCountUnavailableException(new RuntimeException("down"));
        given(chat.countViewers(List.of())).willThrow(cause);

        assertThatThrownBy(() -> counter.countViewers(List.of()))
                .isInstanceOf(ViewerCountUnavailableException.class)
                .hasCause(cause);
    }
}
