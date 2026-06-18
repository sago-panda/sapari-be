package com.sapari.user.infrastructure.persistence.repository;

import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.user.infrastructure.persistence.mapper.UserMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserRepositoryImpl 테스트")
class UserRepositoryImplTest {

    @Mock
    private UserJpaRepository userJpaRepository;

    @Mock
    private UserMapper userMapper;

    @Test
    @DisplayName("userId로 users row를 삭제한다")
    void deleteByIdDeletesUserRow() {
        // given
        UUID userId = UUID.randomUUID();
        UserRepositoryImpl userRepository = new UserRepositoryImpl(userJpaRepository, userMapper);

        // when
        userRepository.deleteById(userId);

        // then
        verify(userJpaRepository).deleteById(userId);
    }
}
