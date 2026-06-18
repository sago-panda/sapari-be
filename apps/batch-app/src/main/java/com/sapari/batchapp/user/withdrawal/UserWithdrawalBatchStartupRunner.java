package com.sapari.batchapp.user.withdrawal;

import java.time.Instant;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;

@Component
// 로컬/초기 테스트용 실행 경로다. 운영 스케줄 방식이 정해지기 전까지는 property로 명시적으로 켤 때만 동작한다.
@ConditionalOnProperty(prefix = "sapari.batch.user.withdrawal.startup", name = "enabled", havingValue = "true")
public class UserWithdrawalBatchStartupRunner implements ApplicationRunner {

    private final JobOperator jobOperator;
    private final Job userWithdrawalHardDeleteJob;
    private final Job userWithdrawalRetentionPurgeJob;
    private final TimeProvider timeProvider;

    public UserWithdrawalBatchStartupRunner(
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

    /**
     * property로 활성화된 경우 앱 시작 직후 탈퇴회원 하드삭제와 보존정보 삭제 Job을 한 번 실행한다.
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 앱 시작 시 두 탈퇴회원 배치를 한 번씩 실행한다.
        JobParameters parameters = createRunAtParameters();
        jobOperator.start(userWithdrawalHardDeleteJob, parameters);
        jobOperator.start(userWithdrawalRetentionPurgeJob, parameters);
    }

    /**
     * 같은 Job을 반복 실행할 수 있도록 매 실행마다 고유한 runAt JobParameter를 만든다.
     */
    private JobParameters createRunAtParameters() {
        Instant runAt = timeProvider.now();
        return new JobParametersBuilder()
                .addString("runAt", runAt.toString())
                .toJobParameters();
    }
}
