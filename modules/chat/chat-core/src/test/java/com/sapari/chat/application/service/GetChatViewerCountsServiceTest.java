package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.sapari.chat.domain.repository.ChatViewerCountRepository;
import com.sapari.chat.exception.ChatViewerCountUnavailableException;

class GetChatViewerCountsServiceTest {

    private final ChatViewerCountRepository repository = mock(ChatViewerCountRepository.class);
    private final GetChatViewerCountsService service = new GetChatViewerCountsService(repository);

    @Test
    @DisplayName("저장소 값을 그대로 돌려준다")
    void passesThrough() {
        UUID room = UUID.randomUUID();
        given(repository.countViewers(List.of(room))).willReturn(Map.of(room, 2L));

        assertThat(service.countViewers(List.of(room))).containsExactly(Map.entry(room, 2L));
    }

    @Test
    @DisplayName("저장소 장애는 호출 도메인이 아는 예외로 번역한다 — 원인은 보존")
    void translatesStoreFailure() {
        RedisConnectionFailureException cause = new RedisConnectionFailureException("down");
        given(repository.countViewers(List.of())).willThrow(cause);

        assertThatThrownBy(() -> service.countViewers(List.of()))
                .isInstanceOf(ChatViewerCountUnavailableException.class)
                .hasCause(cause);
    }
}
