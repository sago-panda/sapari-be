package com.sapari.batchapp.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserWithdrawalHardDeleteProperties 테스트")
class UserWithdrawalHardDeletePropertiesTest {

    @Test
    @DisplayName("retryLimit이 0 이하이면 기본값 3을 사용한다")
    void retryLimitDefaultsToThreeWhenNonPositive() {
        // when
        UserWithdrawalHardDeleteProperties properties = new UserWithdrawalHardDeleteProperties(500, 30, 0);

        // then
        assertThat(properties.retryLimit()).isEqualTo(3);
    }

    @Test
    @DisplayName("retryLimit이 양수이면 설정값을 그대로 사용한다")
    void retryLimitKeepsPositiveValue() {
        // when
        UserWithdrawalHardDeleteProperties properties = new UserWithdrawalHardDeleteProperties(500, 30, 5);

        // then
        assertThat(properties.retryLimit()).isEqualTo(5);
    }
}
