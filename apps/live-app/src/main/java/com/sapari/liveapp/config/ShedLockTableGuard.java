package com.sapari.liveapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 락 테이블에 <b>실제로 한 번 써 보고</b>, 안 되면 부팅을 실패시킨다.
 *
 * <p>이게 없으면 마이그레이션을 빠뜨린 배포가 <b>정상 기동한 뒤</b> 정리 잡 3종만 멈춘다. 매 회차
 * 예외가 나기는 하지만 그 예외는 {@code @SchedulerLock} 프록시가 스케줄러 <b>바깥</b>에서 던지는
 * 것이라, 준비해 둔 도메인 문구("고아 미디어 정리 실패 — 이번 회차 건너뜀") 대신 Spring 기본 핸들러
 * 로그만 남는다. 어느 잡이 왜 멈췄는지 로그에서 읽히지 않는 사이 살아 있는 egress 과금이 계속 나가고
 * 좀비 SFU 방이 쌓인다. {@code LiveSecurityConfig.managementPortMustDiffer} 와 같은 선택 —
 * 조용히 뜨는 대신 시끄럽게 죽는다.
 *
 * <p><b>존재 확인이 아니라 쓰기 확인인 이유</b>: {@code to_regclass} 같은 존재 조회는 "테이블이 있다"만
 * 답한다. 컬럼명·타입이 어긋난 테이블, 또는 {@code INSERT}/{@code UPDATE} 권한이 없는 계정은 그 조회를
 * <b>통과하고</b> 회차마다 터진다 — 가드가 있으나 마나인 조합이다. 그래서 ShedLock 이 실제로 쓰는 것과
 * 같은 모양의 upsert 를 한 번 날린다. 네 컬럼과 쓰기 권한이 한 번에 검증된다.
 *
 * <p>upsert 라 동시 부팅에도 서로를 밀어내지 않는다 — 락 획득을 흉내 내면 경합에서 진 파드가 정상인데도
 * 부팅에 실패한다. 예약 행({@link #BOOT_CHECK}) 하나가 테이블에 남지만 잡 이름이 아니라 아무도 읽지
 * 않는다.
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

    /** 이 가드만 쓰는 예약 행. 잡 이름과 겹치면 안 된다 — 겹치면 부팅이 남의 락을 덮어쓴다. */
    public static final String BOOT_CHECK = "live-boot-check";

    /**
     * 시각은 ShedLock 과 <b>같은 식</b>({@code timezone('utc', CURRENT_TIMESTAMP)})으로 넣는다. 다른 식을
     * 쓰면 가드는 통과하는데 실제 락만 어긋나는 상태를 못 잡는다.
     */
    private static final String WRITE_PROBE = """
            INSERT INTO %s (name, lock_until, locked_at, locked_by)
            VALUES (?, timezone('utc', CURRENT_TIMESTAMP), timezone('utc', CURRENT_TIMESTAMP), ?)
            ON CONFLICT (name) DO UPDATE
               SET lock_until = EXCLUDED.lock_until,
                   locked_at  = EXCLUDED.locked_at,
                   locked_by  = EXCLUDED.locked_by
            """.formatted(ReconcileLockConfig.LOCK_TABLE);

    public ShedLockTableGuard(JdbcTemplate jdbcTemplate) {
        try {
            jdbcTemplate.update(WRITE_PROBE, BOOT_CHECK, "boot-check");
        } catch (DataAccessException e) {
            // 여기서 삼키면 가드가 있으나 마나다. 원인을 다 가릴 수는 없으므로(테이블 부재·컬럼 불일치·
            // 권한 부족·연결 실패가 모두 DataAccessException 이다) 확인할 것을 순서대로 적어 준다.
            throw new IllegalStateException(
                    "락 테이블(" + ReconcileLockConfig.LOCK_TABLE + ")에 쓸 수 없다 — 정리 스케줄러가 분산 "
                            + "락을 잡지 못해 전부 멈춘다. 차례로 확인할 것: "
                            + "(1) 앱보다 먼저 마이그레이션을 돌렸는가"
                            + "(DB_URL=… DB_USER=… DB_PASSWORD=… ./infra/migration/migrate.sh, "
                            + "db/migration/live/V*__shedlock.sql), "
                            + "(2) DB 계정에 이 테이블 INSERT/UPDATE 권한이 있는가, "
                            + "(3) DB 에 연결되는가. "
                            + "정리 잡이 필요 없는 환경이면 live.reconcile.enabled=false 로 함께 내릴 것.", e);
        }
    }
}
