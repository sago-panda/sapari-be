package com.sapari.batchapp.user.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sapari.batch.user.withdrawal.retention-purge")
public record UserWithdrawalRetentionPurgeProperties(
        int chunkSize,
        int retryLimit
) {

    /**
     * 잘못된 설정값이 들어오면 안전한 기본값으로 보정한다.
     */
    public UserWithdrawalRetentionPurgeProperties {
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (retryLimit <= 0) {
            retryLimit = 3;
        }
    }
}
