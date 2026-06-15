package com.sapari.batchapp.user.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sapari.batch.user.withdrawal.scheduler")
public record UserWithdrawalBatchSchedulerProperties(
        String zone,
        String hardDeleteCron,
        String retentionPurgeCron
) {
}
