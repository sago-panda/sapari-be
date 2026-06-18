package com.sapari.batchapp.user.withdrawal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

import com.sapari.global.time.TimeProvider;

@Configuration(proxyBeanMethods = false)
public class UserWithdrawalBatchConfig {

    public static final String HARD_DELETE_JOB_NAME = "userWithdrawalHardDeleteJob";
    public static final String HARD_DELETE_STEP_NAME = "userWithdrawalHardDeleteStep";
    public static final String RETENTION_PURGE_JOB_NAME = "userWithdrawalRetentionPurgeJob";
    public static final String RETENTION_PURGE_STEP_NAME = "userWithdrawalRetentionPurgeStep";

    /**
     * 탈퇴 유예 기간이 지난 사용자 계정을 실제 삭제하는 Job을 구성한다.
     */
    @Bean
    public Job userWithdrawalHardDeleteJob(
            JobRepository jobRepository,
            Step userWithdrawalHardDeleteStep
    ) {
        return new JobBuilder(HARD_DELETE_JOB_NAME, jobRepository)
                .start(userWithdrawalHardDeleteStep)
                .build();
    }

    /**
     * WITHDRAWING 상태이고 deleted_at이 보존 기준일을 지난 사용자 ID를 chunk 단위로 삭제한다.
     */
    @Bean
    public Step userWithdrawalHardDeleteStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<UUID> userWithdrawalHardDeleteReader,
            UserWithdrawalHardDeleteWriter userWithdrawalHardDeleteWriter,
            UserWithdrawalHardDeleteProperties properties
    ) {
        return new StepBuilder(HARD_DELETE_STEP_NAME, jobRepository)
                .<UUID, UUID>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(userWithdrawalHardDeleteReader)
                .writer(userWithdrawalHardDeleteWriter)
                // DB 잠금/데드락/일시 장애는 같은 chunk를 재시도하면 성공할 수 있다.
                .faultTolerant()
                .retryLimit(properties.retryLimit())
                .retry(TransientDataAccessException.class)
                .retry(CannotAcquireLockException.class)
                .build();
    }

    /**
     * 법정 보존 기간이 끝난 탈퇴회원 보존 정보를 삭제하는 Job을 구성한다.
     */
    @Bean
    public Job userWithdrawalRetentionPurgeJob(
            JobRepository jobRepository,
            Step userWithdrawalRetentionPurgeStep
    ) {
        return new JobBuilder(RETENTION_PURGE_JOB_NAME, jobRepository)
                .start(userWithdrawalRetentionPurgeStep)
                .build();
    }

    /**
     * retention_until이 지난 withdrawn_user_retentions row를 chunk 단위로 삭제한다.
     */
    @Bean
    public Step userWithdrawalRetentionPurgeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<UUID> userWithdrawalRetentionPurgeReader,
            UserWithdrawalRetentionPurgeWriter userWithdrawalRetentionPurgeWriter,
            UserWithdrawalRetentionPurgeProperties properties
    ) {
        return new StepBuilder(RETENTION_PURGE_STEP_NAME, jobRepository)
                .<UUID, UUID>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(userWithdrawalRetentionPurgeReader)
                .writer(userWithdrawalRetentionPurgeWriter)
                // DB 잠금/데드락/일시 장애는 같은 chunk를 재시도하면 성공할 수 있다.
                .faultTolerant()
                .retryLimit(properties.retryLimit())
                .retry(TransientDataAccessException.class)
                .retry(CannotAcquireLockException.class)
                .build();
    }

    /**
     * 탈퇴 유예 기간이 지난 사용자 ID를 오래된 deleted_at 순으로 읽는다.
     */
    @Bean
    public JdbcPagingItemReader<UUID> userWithdrawalHardDeleteReader(
            DataSource dataSource,
            TimeProvider timeProvider,
            UserWithdrawalHardDeleteProperties properties
    ) throws Exception {
        Instant threshold = timeProvider.now().minus(properties.retentionDays(), ChronoUnit.DAYS);

        return new JdbcPagingItemReaderBuilder<UUID>()
                .name("userWithdrawalHardDeleteReader")
                .dataSource(dataSource)
                .selectClause("SELECT id")
                .fromClause("FROM user_schema.users")
                .whereClause("WHERE status = 'WITHDRAWING' AND deleted_at <= :threshold")
                .sortKeys(Map.of(
                        "deleted_at", Order.ASCENDING,
                        "id", Order.ASCENDING
                ))
                .parameterValues(Map.of("threshold", Timestamp.from(threshold)))
                .rowMapper((rs, rowNum) -> rs.getObject("id", UUID.class))
                .pageSize(properties.chunkSize())
                .build();
    }

    /**
     * 법정 보존 만료 시각이 지난 원 사용자 ID를 오래된 retention_until 순으로 읽는다.
     */
    @Bean
    public JdbcPagingItemReader<UUID> userWithdrawalRetentionPurgeReader(
            DataSource dataSource,
            TimeProvider timeProvider,
            UserWithdrawalRetentionPurgeProperties properties
    ) throws Exception {
        Instant now = timeProvider.now();

        return new JdbcPagingItemReaderBuilder<UUID>()
                .name("userWithdrawalRetentionPurgeReader")
                .dataSource(dataSource)
                .selectClause("SELECT original_user_id")
                .fromClause("FROM user_schema.withdrawn_user_retentions")
                .whereClause("WHERE purged_at IS NULL AND retention_until <= :now")
                .sortKeys(Map.of(
                        "retention_until", Order.ASCENDING,
                        "original_user_id", Order.ASCENDING
                ))
                .parameterValues(Map.of("now", Timestamp.from(now)))
                .rowMapper((rs, rowNum) -> rs.getObject("original_user_id", UUID.class))
                .pageSize(properties.chunkSize())
                .build();
    }
}
