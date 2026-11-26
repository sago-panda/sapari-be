package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.sapari.chat.domain.repository.ChatMessageCountRepository;
import com.sapari.chat.exception.ChatMessageCountUnavailableException;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetChatMessageCountService — 저장소 값을 그대로, 장애는 호출 도메인이 아는 예외로")
class GetChatMessageCountServiceTest {

    @Mock
    private ChatMessageCountRepository countRepository;

    @InjectMocks
    private GetChatMessageCountService service;

    private final UUID roomId = UUID.randomUUID();

    @Test
    @DisplayName("저장소가 센 값을 돌려준다")
    void returnsTheStoredCount() {
        given(countRepository.countMessages(roomId)).willReturn(42L);

        assertThat(service.countMessages(roomId)).isEqualTo(42L);
    }

    @Test
    @DisplayName("⭐ 저장소 장애는 0 이 아니라 ChatMessageCountUnavailableException 이다")
    void translatesStorageFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("mongo down");
        given(countRepository.countMessages(roomId)).willThrow(failure);

        assertThatThrownBy(() -> service.countMessages(roomId))
                .isInstanceOf(ChatMessageCountUnavailableException.class)
                .hasCause(failure);
    }
}
