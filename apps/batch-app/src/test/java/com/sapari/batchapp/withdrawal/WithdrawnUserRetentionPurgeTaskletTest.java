package com.sapari.batchapp.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.sapari.global.time.TimeProvider;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawnUserRetentionPurgeTasklet 테스트")
class WithdrawnUserRetentionPurgeTaskletTest {

    @Mock
    private WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;

    @Mock
    private TimeProvider timeProvider;

    @Test
    @DisplayName("현재 시각 기준 만료된 탈퇴회원 보존 row를 삭제하고 종료한다")
    void executeDeletesExpiredRetentions() throws Exception {
        // given
        Instant now = Instant.parse("2031-06-15T09:40:00Z");
        when(timeProvider.now()).thenReturn(now);
        WithdrawnUserRetentionPurgeTasklet tasklet = new WithdrawnUserRetentionPurgeTasklet(
                withdrawnUserRetentionRepository,
                timeProvider
        );

        // when
        RepeatStatus result = tasklet.execute(null, null);

        // then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(withdrawnUserRetentionRepository).deleteExpiredBefore(now);
    }
}
