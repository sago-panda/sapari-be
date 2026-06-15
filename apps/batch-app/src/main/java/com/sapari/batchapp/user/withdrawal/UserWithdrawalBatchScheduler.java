package com.sapari.batchapp.user.withdrawal;

import java.time.Instant;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;

@Component
@ConditionalOnProperty(prefix = "sapari.batch.user.withdrawal.scheduler", name = "enabled", havingValue = "true")
public class UserWithdrawalBatchScheduler {

    private final JobOperator jobOperator;
    private final Job userWithdrawalHardDeleteJob;
    private final Job userWithdrawalRetentionPurgeJob;
    private final TimeProvider timeProvider;

    public UserWithdrawalBatchScheduler(
            JobOperator jobOperator,
            @Qualifier("userWithdrawalHardDeleteJob") Job userWithdrawalHardDeleteJob,
            @Qualifier("userWithdrawalRetentionPurgeJob") Job userWithdrawalRetentionPurgeJob,
            TimeProvider timeProvider
    ) {
        this.jobOperator = jobOperator;
        this.userWithdrawalHardDeleteJob = userWithdrawalHardDeleteJob;
        this.userWithdrawalRetentionPurgeJob = userWithdrawalRetentionPurgeJob;
        this.timeProvider = timeProvider;
    }

    @Scheduled(
            cron = "#{@userWithdrawalBatchSchedulerProperties.hardDeleteCron}",
            zone = "#{@userWithdrawalBatchSchedulerProperties.zone}"
    )
    public void runHardDeleteJob() throws Exception {
        jobOperator.start(userWithdrawalHardDeleteJob, createRunAtParameters());
    }

    // hard delete와 보존정보 파기 작업은 부하와 실패 이력을 분리하기 위해 별도 cron으로 실행한다.
    @Scheduled(
            cron = "#{@userWithdrawalBatchSchedulerProperties.retentionPurgeCron}",
            zone = "#{@userWithdrawalBatchSchedulerProperties.zone}"
    )
    public void runRetentionPurgeJob() throws Exception {
        jobOperator.start(userWithdrawalRetentionPurgeJob, createRunAtParameters());
    }

    private JobParameters createRunAtParameters() {
        Instant runAt = timeProvider.now();
        return new JobParametersBuilder()
                // 같은 Job을 반복 실행할 수 있도록 매 실행마다 고유한 JobInstance를 만든다.
                .addString("runAt", runAt.toString())
                .toJobParameters();
    }
}
