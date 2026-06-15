package com.sapari.batchapp.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;

import com.sapari.global.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWithdrawalBatchScheduler 테스트")
class UserWithdrawalBatchSchedulerTest {

    @Mock
    private JobOperator jobOperator;

    @Mock
    private Job userWithdrawalHardDeleteJob;

    @Mock
    private Job userWithdrawalRetentionPurgeJob;

    @Mock
    private TimeProvider timeProvider;

    @Test
    @DisplayName("hard delete 스케줄은 runAt 파라미터로 회원탈퇴 hard delete Job을 실행한다")
    void runHardDeleteJobLaunchesHardDeleteJobWithRunAtParameter() throws Exception {
        // given
        Instant now = Instant.parse("2026-06-15T03:00:00Z");
        org.mockito.Mockito.when(timeProvider.now()).thenReturn(now);
        UserWithdrawalBatchScheduler scheduler = new UserWithdrawalBatchScheduler(
                jobOperator,
                userWithdrawalHardDeleteJob,
                userWithdrawalRetentionPurgeJob,
                timeProvider
        );

        // when
        scheduler.runHardDeleteJob();

        // then
        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(userWithdrawalHardDeleteJob), parametersCaptor.capture());
        assertThat(parametersCaptor.getValue().getString("runAt")).isEqualTo(now.toString());
    }

    @Test
    @DisplayName("retention purge 스케줄은 runAt 파라미터로 탈퇴회원 보존정보 파기 Job을 실행한다")
    void runRetentionPurgeJobLaunchesRetentionPurgeJobWithRunAtParameter() throws Exception {
        // given
        Instant now = Instant.parse("2026-06-15T03:30:00Z");
        org.mockito.Mockito.when(timeProvider.now()).thenReturn(now);
        UserWithdrawalBatchScheduler scheduler = new UserWithdrawalBatchScheduler(
                jobOperator,
                userWithdrawalHardDeleteJob,
                userWithdrawalRetentionPurgeJob,
                timeProvider
        );

        // when
        scheduler.runRetentionPurgeJob();

        // then
        ArgumentCaptor<JobParameters> parametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(userWithdrawalRetentionPurgeJob), parametersCaptor.capture());
        assertThat(parametersCaptor.getValue().getString("runAt")).isEqualTo(now.toString());
    }
}
