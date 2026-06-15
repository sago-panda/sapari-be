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
import org.springframework.transaction.PlatformTransactionManager;

import com.sapari.global.time.TimeProvider;

@Configuration(proxyBeanMethods = false)
public class UserWithdrawalBatchConfig {

    public static final String HARD_DELETE_JOB_NAME = "userWithdrawalHardDeleteJob";
    public static final String HARD_DELETE_STEP_NAME = "userWithdrawalHardDeleteStep";
    public static final String RETENTION_PURGE_JOB_NAME = "userWithdrawalRetentionPurgeJob";
    public static final String RETENTION_PURGE_STEP_NAME = "userWithdrawalRetentionPurgeStep";

    @Bean
    public Job userWithdrawalHardDeleteJob(
            JobRepository jobRepository,
            Step userWithdrawalHardDeleteStep
    ) {
        return new JobBuilder(HARD_DELETE_JOB_NAME, jobRepository)
                .start(userWithdrawalHardDeleteStep)
                .build();
    }

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
                .build();
    }

    @Bean
    public Job userWithdrawalRetentionPurgeJob(
            JobRepository jobRepository,
            Step userWithdrawalRetentionPurgeStep
    ) {
        return new JobBuilder(RETENTION_PURGE_JOB_NAME, jobRepository)
                .start(userWithdrawalRetentionPurgeStep)
                .build();
    }

    @Bean
    public Step userWithdrawalRetentionPurgeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            UserWithdrawalRetentionPurgeTasklet userWithdrawalRetentionPurgeTasklet
    ) {
        return new StepBuilder(RETENTION_PURGE_STEP_NAME, jobRepository)
                .tasklet(userWithdrawalRetentionPurgeTasklet, transactionManager)
                .build();
    }

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
}
