package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sapari.liveapp.scheduler.EndStaleLiveScheduler;
import com.sapari.liveapp.scheduler.ExpireReadyScheduler;
import com.sapari.liveapp.scheduler.OrphanMediaScheduler;

/**
 * 락 테이블 부팅 가드.
 *
 * <p>Docker 없이 도는 테스트다 — 쓰기 프로브가 진짜 Postgres 에서 통하는지는
 * {@code ReconcileSchedulerLockTest} 가 실제 컨테이너와 실제 마이그레이션으로 확인한다.
 * 여기서는 <b>실패 방향</b>만 본다: 못 쓰면 반드시 부팅이 멈춰야 한다.
 */
class ShedLockTableGuardTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    @DisplayName("쓸 수 있으면 부팅한다")
    void bootsWhenTheTableIsWritable() {
        given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

        assertThatCode(() -> new ShedLockTableGuard(jdbcTemplate)).doesNotThrowAnyException();
    }

    /**
     * 세 원인이 <b>같은 경로</b>를 탄다 — 가드는 {@code DataAccessException} 하나로 받고 메시지도 하나다.
     * 그래서 원인별 단언을 나눠 쓰면 이름만 다르고 아무것도 구분하지 못하는 테스트가 된다.
     * 대신 "어떤 원인이든 반드시 죽는다"와 "메시지가 볼 곳을 다 알려준다"를 확인한다 — 원인을 코드가
     * 가릴 수 없으므로 그게 이 가드가 할 수 있는 전부다.
     */
    static List<DataAccessException> writeFailures() {
        return List.of(
                new BadSqlGrammarException("probe", "insert", new SQLException()),  // 테이블 부재·컬럼 불일치
                new PermissionDeniedDataAccessException("권한 없음", null),          // INSERT/UPDATE 권한 부족
                new CannotGetJdbcConnectionException("DB 안 붙음"));                 // 연결 실패
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeFailures")
    @DisplayName("쓰기가 실패하면 원인과 무관하게 부팅을 거부하고, 무엇을 볼지 순서대로 알려준다")
    void refusesToBootOnAnyWriteFailure(DataAccessException failure) {
        willThrow(failure).given(jdbcTemplate).update(anyString(), any(Object[].class));

        assertThatThrownBy(() -> new ShedLockTableGuard(jdbcTemplate))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(failure)
                .hasMessageContaining(ReconcileLockConfig.LOCK_TABLE)
                .hasMessageContaining("migrate.sh")          // (1) 마이그레이션
                .hasMessageContaining("INSERT/UPDATE 권한")   // (2) 권한
                .hasMessageContaining("DB 에 연결되는가");     // (3) 연결
    }

    /**
     * 예약 행이 잡 락 이름과 겹치면 <b>부팅이 남의 락을 덮어쓴다</b>. 접두사 규칙이 아니라 실제로 걸려
     * 있는 이름과 대조한다 — 규칙만 비교하면 잡 이름이 규칙을 벗어났을 때 이 테스트가 눈감는다.
     */
    @Test
    @DisplayName("예약 행 이름이 실제 잡 락 이름 어느 것과도 겹치지 않는다")
    void bootCheckRowDoesNotCollideWithJobLocks() {
        List<String> jobLockNames = List.of(
                        ExpireReadyScheduler.class, EndStaleLiveScheduler.class, OrphanMediaScheduler.class)
                .stream()
                .map(job -> {
                    try {
                        return job.getMethod("run").getAnnotation(SchedulerLock.class).name();
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();

        assertThat(jobLockNames).hasSize(3).doesNotContain(ShedLockTableGuard.BOOT_CHECK);
    }
}
