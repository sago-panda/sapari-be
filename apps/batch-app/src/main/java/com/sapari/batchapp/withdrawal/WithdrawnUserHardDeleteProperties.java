package com.sapari.batchapp.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sapari.batch.withdrawal.hard-delete")
public record WithdrawnUserHardDeleteProperties(
        int chunkSize,
        int retentionDays
) {

    public WithdrawnUserHardDeleteProperties {
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (retentionDays <= 0) {
            retentionDays = 30;
        }
    }
}
