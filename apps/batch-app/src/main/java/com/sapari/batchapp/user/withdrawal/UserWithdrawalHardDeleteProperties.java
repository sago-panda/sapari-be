package com.sapari.batchapp.user.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sapari.batch.user.withdrawal.hard-delete")
public record UserWithdrawalHardDeleteProperties(
        int chunkSize,
        int retentionDays
) {

    public UserWithdrawalHardDeleteProperties {
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (retentionDays <= 0) {
            retentionDays = 30;
        }
    }
}
