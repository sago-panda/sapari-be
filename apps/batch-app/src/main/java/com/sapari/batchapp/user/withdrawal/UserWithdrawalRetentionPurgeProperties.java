package com.sapari.batchapp.user.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sapari.batch.user.withdrawal.retention-purge")
public record UserWithdrawalRetentionPurgeProperties(
        int chunkSize,
        int retryLimit
) {

    public UserWithdrawalRetentionPurgeProperties {
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (retryLimit <= 0) {
            retryLimit = 3;
        }
    }
}
