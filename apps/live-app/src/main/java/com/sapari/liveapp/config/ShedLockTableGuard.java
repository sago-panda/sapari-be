package com.sapari.liveapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 락 테이블이 없으면 <b>부팅을 실패시킨다</b>.
 *
 * <p>이게 없으면 마이그레이션을 빠뜨린 배포가 <b>정상 기동한 뒤</b> 정리 잡 3종만 조용히 멈춘다.
 * 매 회차 {@code BadSqlGrammarException} 이 나기는 하지만 그 예외는 {@code @SchedulerLock} 프록시가
 * 스케줄러 <b>바깥</b>에서 던지는 것이라, 준비해 둔 도메인 문구("고아 미디어 정리 실패 — 이번 회차
 * 건너뜀") 대신 Spring 기본 핸들러 로그만 남는다. 어느 잡이 왜 멈췄는지 로그에서 읽히지 않는 사이
 * 살아 있는 egress 과금이 계속 나가고 좀비 SFU 방이 쌓인다.
 *
 * <p>그래서 {@code LiveSecurityConfig.managementPortMustDiffer} 와 같은 선택을 한다 — 조용히 뜨는
 * 대신 시끄럽게 죽는다. 설정 실수 한 줄과 비용 누수 사이에 아무 신호가 없는 조합이 같기 때문이다.
 *
 * <p><b>부팅 시 DB 의존을 새로 만들지 않는다</b>: {@code ddl-auto: validate} 가 이미 부팅 시점에 DB 를
 * 요구한다. 다만 그 검증은 <b>엔티티 매핑</b>만 보므로 엔티티가 없는 이 테이블은 잡지 못한다 —
 * 이 가드가 메우는 자리가 정확히 거기다.
 *
 * <p>정리 잡을 통째로 내린 환경({@code live.reconcile.enabled=false})에서는 락도 필요 없으므로 함께
 * 내려간다. 락 설정과 같은 스위치를 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "live.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShedLockTableGuard {

    /** {@link ReconcileLockConfig#lockConfiguration} 이 쓰는 테이블과 <b>같은 값</b>이어야 한다. */
    static final String TABLE = "live_schema.shedlock";

    public ShedLockTableGuard(JdbcTemplate jdbcTemplate) {
        Object found;
        try {
            // to_regclass 는 없는 이름에 예외 대신 null 을 준다 — "테이블 없음"과 "조회 실패"를 가른다.
            found = jdbcTemplate.queryForObject("SELECT to_regclass(?)", Object.class, TABLE);
        } catch (DataAccessException e) {
            // 여기서 삼키면 가드가 있으나 마나다. 다만 원인은 구분해 준다 — DB 가 안 붙는 것과
            // 테이블이 없는 것은 해야 할 일이 다르다.
            throw new IllegalStateException(
                    "락 테이블(" + TABLE + ") 확인에 실패했다 — DB 연결을 먼저 볼 것.", e);
        }
        if (found == null) {
            throw new IllegalStateException(
                    "락 테이블(" + TABLE + ")이 없다 — 정리 스케줄러가 분산 락을 잡지 못해 전부 멈춘다. "
                            + "앱보다 먼저 마이그레이션을 돌릴 것"
                            + "(DB_URL=… DB_USER=… DB_PASSWORD=… ./infra/migration/migrate.sh, "
                            + "db/migration/live/V*__shedlock.sql). "
                            + "정리 잡이 필요 없는 환경이면 live.reconcile.enabled=false 로 함께 내릴 것.");
        }
    }
}
