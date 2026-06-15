package com.sapari.user.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.user.infrastructure.persistence.mapper.WithdrawnUserRetentionMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawnUserRetentionRepositoryImpl 테스트")
class WithdrawnUserRetentionRepositoryImplTest {

    @Mock
    private WithdrawnUserRetentionJpaRepository withdrawnUserRetentionJpaRepository;

    @Mock
    private WithdrawnUserRetentionMapper withdrawnUserRetentionMapper;

    @Test
    @DisplayName("보존기간이 지난 탈퇴회원 보존 row를 삭제한다")
    void deleteExpiredBeforeDeletesExpiredRows() {
        // given
        Instant now = Instant.parse("2031-06-15T09:40:00Z");
        WithdrawnUserRetentionRepositoryImpl repository = new WithdrawnUserRetentionRepositoryImpl(
                withdrawnUserRetentionJpaRepository,
                withdrawnUserRetentionMapper
        );
        when(withdrawnUserRetentionJpaRepository.deleteByRetentionUntilLessThanEqual(now)).thenReturn(3);

        // when
        int deletedCount = repository.deleteExpiredBefore(now);

        // then
        assertThat(deletedCount).isEqualTo(3);
        verify(withdrawnUserRetentionJpaRepository).deleteByRetentionUntilLessThanEqual(now);
    }
}
