package com.sapari.batchapp.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
@DisplayName("UserWithdrawalBatchStartupRunner 테스트")
class UserWithdrawalBatchStartupRunnerTest {

    @Mock
    private JobOperator jobOperator;

    @Mock
    private Job userWithdrawalHardDeleteJob;

    @Mock
    private Job userWithdrawalRetentionPurgeJob;

    @Mock
    private TimeProvider timeProvider;

    @Test
    @DisplayName("앱 시작 시 runAt 파라미터로 탈퇴회원 배치 Job들을 1회 실행한다")
    void runLaunchesUserWithdrawalJobsWithRunAtParameter() throws Exception {
        // given
        Instant now = Instant.parse("2026-06-15T03:00:00Z");
        when(timeProvider.now()).thenReturn(now);
        UserWithdrawalBatchStartupRunner runner = new UserWithdrawalBatchStartupRunner(
                jobOperator,
                userWithdrawalHardDeleteJob,
                userWithdrawalRetentionPurgeJob,
                timeProvider
        );

        // when
        runner.run(null);

        // then
        ArgumentCaptor<JobParameters> hardDeleteParameters = ArgumentCaptor.forClass(JobParameters.class);
        ArgumentCaptor<JobParameters> retentionPurgeParameters = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(userWithdrawalHardDeleteJob), hardDeleteParameters.capture());
        verify(jobOperator).start(eq(userWithdrawalRetentionPurgeJob), retentionPurgeParameters.capture());
        assertThat(hardDeleteParameters.getValue().getString("runAt")).isEqualTo(now.toString());
        assertThat(retentionPurgeParameters.getValue().getString("runAt")).isEqualTo(now.toString());
    }
}
