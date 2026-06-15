package com.sapari.batchapp.withdrawal;

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
public class WithdrawnUserHardDeleteJobConfig {

    public static final String JOB_NAME = "withdrawnUserHardDeleteJob";
    public static final String STEP_NAME = "withdrawnUserHardDeleteStep";

    @Bean
    public Job withdrawnUserHardDeleteJob(
            JobRepository jobRepository,
            Step withdrawnUserHardDeleteStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(withdrawnUserHardDeleteStep)
                .build();
    }

    @Bean
    public Step withdrawnUserHardDeleteStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<UUID> withdrawnUserHardDeleteReader,
            WithdrawnUserHardDeleteWriter withdrawnUserHardDeleteWriter,
            WithdrawnUserHardDeleteProperties properties
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<UUID, UUID>chunk(properties.chunkSize(), transactionManager)
                .reader(withdrawnUserHardDeleteReader)
                .writer(withdrawnUserHardDeleteWriter)
                .build();
    }

    @Bean
    public JdbcPagingItemReader<UUID> withdrawnUserHardDeleteReader(
            DataSource dataSource,
            TimeProvider timeProvider,
            WithdrawnUserHardDeleteProperties properties
    ) throws Exception {
        Instant threshold = timeProvider.now().minus(properties.retentionDays(), ChronoUnit.DAYS);

        return new JdbcPagingItemReaderBuilder<UUID>()
                .name("withdrawnUserHardDeleteReader")
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
