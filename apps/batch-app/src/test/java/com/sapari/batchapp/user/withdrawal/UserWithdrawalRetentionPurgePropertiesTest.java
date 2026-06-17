package com.sapari.batchapp.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserWithdrawalRetentionPurgeProperties 테스트")
class UserWithdrawalRetentionPurgePropertiesTest {

    @Test
    @DisplayName("chunkSize와 retryLimit이 0 이하이면 기본값을 사용한다")
    void defaultsWhenNonPositive() {
        // when
        UserWithdrawalRetentionPurgeProperties properties = new UserWithdrawalRetentionPurgeProperties(0, 0);

        // then
        assertThat(properties.chunkSize()).isEqualTo(500);
        assertThat(properties.retryLimit()).isEqualTo(3);
    }

    @Test
    @DisplayName("chunkSize와 retryLimit이 양수이면 설정값을 그대로 사용한다")
    void keepsPositiveValues() {
        // when
        UserWithdrawalRetentionPurgeProperties properties = new UserWithdrawalRetentionPurgeProperties(100, 5);

        // then
        assertThat(properties.chunkSize()).isEqualTo(100);
        assertThat(properties.retryLimit()).isEqualTo(5);
    }
}
