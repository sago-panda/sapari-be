package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 락 테이블 부팅 가드.
 *
 * <p>Docker 없이 도는 테스트다 — 실제 SQL 이 Postgres 에서 동작하는지는
 * {@code ReconcileSchedulerLockTest} 가 진짜 컨테이너로 확인한다. 여기서는 <b>판정과 실패 방향</b>만 본다.
 */
class ShedLockTableGuardTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    @DisplayName("테이블이 있으면 부팅한다")
    void bootsWhenTableExists() {
        given(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .willReturn("shedlock");

        assertThatCode(() -> new ShedLockTableGuard(jdbcTemplate)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("테이블이 없으면 부팅을 거부한다 — 조용히 떠서 정리 잡만 멈추는 게 최악이다")
    void refusesToBootWhenTableIsMissing() {
        // to_regclass 는 없는 이름에 null 을 준다(예외가 아니다).
        given(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .willReturn(null);

        assertThatThrownBy(() -> new ShedLockTableGuard(jdbcTemplate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live_schema.shedlock")
                .hasMessageContaining("migrate.sh");
    }

    @Test
    @DisplayName("조회 자체가 실패하면 테이블 부재와 구분해 알린다 — 해야 할 일이 다르다")
    void distinguishesQueryFailureFromMissingTable() {
        willThrow(new CannotAcquireLockException("DB 안 붙음"))
                .given(jdbcTemplate).queryForObject(anyString(), any(Class.class), any(Object[].class));

        assertThatThrownBy(() -> new ShedLockTableGuard(jdbcTemplate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB 연결");
    }
}
