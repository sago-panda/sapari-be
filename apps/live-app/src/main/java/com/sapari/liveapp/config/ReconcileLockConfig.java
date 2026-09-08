package com.sapari.liveapp.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.infrastructure.config.LiveReconcileProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

/**
 * 정리 잡 3종의 분산 락. <b>레플리카를 2 이상으로 올리기 위한 전제 조건</b>이다.
 *
 * <p>왜 필요한가 — 세 잡 중 {@code orphan-media} 만 DB 전이가 없다. 나머지 둘은 판정 후 행 잠금 +
 * 상태 가드를 지나 커밋한 쪽만 {@code PostCommitMediaCleanup} 으로 외부 정리를 등록하므로, 두
 * 인스턴스가 같은 방을 집어도 LiveKit 호출은 한 번뿐이다. {@code orphan-media} 는 "전수 조회 → 판정 →
 * 삭제"가 전부라 그 관문이 없고, 정리 계열 포트가 실패를 삼키도록 설계돼 있어(도메인 규약)
 * <b>중복 호출이 예외로 드러나지도 않는다</b>. 대신 {@code reconcileActed} 집계가 배로 부풀어,
 * "같은 수치가 반복되면 안 치워지는 중"이라는 정리 잡의 유일한 판독법이 무너진다.
 *
 * <p>락 저장소가 Postgres 인 이유: live-app 에는 Redis 의존성이 아예 없고(추가하면 표면이 는다),
 * 이 락이 지키는 건 되돌릴 수 없는 외부 삭제라 "날아가도 되는 캐시" 취급인 Redis 에 두기에 맞지 않다.
 * 테이블이 남으므로 "지금 누가 쥐고 있나"를 조회로 확인할 수 있다는 점도 운영상 크다. 다만 그 조회에는
 * {@link ShedLockTableGuard#BOOT_CHECK} 행이 상시 섞인다 — 부팅 프로브가 남기는 것이라 잡이 아니고,
 * 항상 만료 상태로 보인다. 잡 락만 보려면 이름이 {@code live-reconcile-} 로 시작하는 행만 볼 것.
 *
 * <p>{@code usingDbTime()} — 만료 판정을 DB {@code now()} 로 한다. 인스턴스 시계가 서로 어긋나도
 * 상호배제가 깨지지 않는다.
 *
 * <p><b>락 유지 시간</b>({@code live.reconcile.<job>.lock-at-most-for}) 은 곧 <b>락 보유 인스턴스가
 * 죽었을 때의 인계 지연</b>이다. ShedLock 은 실행 중인 회차를 중단시키지도 리스를 연장하지도 않으므로,
 * 회차가 이 값을 넘기면 락이 만료된 채로 계속 돌고 다음 tick 의 다른 인스턴스가 같은 회차를 겹쳐 돈다
 * — 즉 <b>짧게 주면 락을 건 이유가 사라진다</b>.
 *
 * <p><b>인과가 뒤집혔다</b>: 예전에는 "잡의 최악 실행 시간"을 추정해 그보다 길게 리스를 잡았다. 그
 * 추정이 성립하지 않는다는 게 드러나(후보당 왕복 수가 방의 리소스 수에 비례해 설정으로 늘어난다)
 * 이제는 <b>리스가 회차 예산의 원천</b>이다 — {@code roundBudget = lock-at-most-for × 4/5} 이고 회차는
 * 그 예산에서 멈춘다({@code LiveReconcileProperties}). 즉 이 값을 정하는 건 "얼마나 걸릴까"가 아니라
 * <b>"인계를 얼마나 늦춰도 되는가"</b>이고, 처리량은 거기서 따라온다.
 *
 * <p>프로퍼티로 뺀 건 인계 테스트가 이 값을 초 단위로 줄여야 하기 때문이다.
 *
 * <p>{@code live.reconcile.lock-at-least-for} 는 회차가 순식간에 끝났을 때를 막는다. 두 인스턴스의
 * cron 발화가 지터로 수백 ms 어긋나면 먼저 돈 쪽이 이미 락을 놓은 뒤라 뒤에 온 쪽이 같은 회차를 한 번
 * 더 돈다. <b>cron 간격보다 짧게 유지할 것</b> — 주기를 이 값 아래로 내리면 회차를 조용히 건너뛴다.
 *
 * <p>{@code @SchedulerLock} 은 {@code run()} 바깥을 감싸므로 회차 지표만으로는 락 경합과 죽은
 * 스케줄러를 구분할 수 없다. {@link TrackingLockProvider} 가 획득·스킵·실패를
 * {@code live.reconcile.lock} 으로 따로 기록한다({@code shutdown} 은 종료 중 새 회차를 막은 결과다).
 * <b>실행 중인 회차의 락을 대신 반납하지는 않는다</b> — 이유는 {@link TrackingLockProvider} 클래스 주석.
 *
 * <p>락 저장소 장애는 <b>조용하지 않다</b>. {@code throwUnexpectedException} 기본값이 {@code false} 라
 * "장애가 전부 빈 값으로 수렴한다"고 읽기 쉬운데, 실제로 부딪히는 두 경우는 모두 예외로 올라온다
 * (ShedLock 6.6.0 + Postgres 실측):
 *
 * <pre>
 *   테이블·스키마 부재  → BadSqlGrammarException           (insert 는 삼키지만 update 폴백이 던진다)
 *   DB 정지·연결 불가   → CannotCreateTransactionException
 * </pre>
 *
 * 삼켜지는 건 {@code DuplicateKey}·{@code ConcurrencyFailure}·{@code DataIntegrityViolation} 처럼
 * <b>경합에 가까운</b> 종류뿐이고, 그건 빈 값(= 다음 회차 재시도)으로 처리하는 게 맞다.
 *
 * <p>이 예외는 프록시가 유스케이스 바깥에서 던져 {@code reconcileRoundFailed} 대신 락 결과
 * {@code failed} 로 기록된다. 원래 예외는 그대로 전파돼 Spring 기본 핸들러 로그도 남는다.
 *
 * <p>{@code .withThrowUnexpectedException(true)} 는 켜지 않았다. 위 두 경우가 이미 던지므로 남는 건
 * 경합성 예외뿐이고, 그걸 던지게 하면 정상 경합까지 스택트레이스가 되어 <b>진짜 장애 로그가 묻힌다</b>.
 *
 * <p>스케줄링 설정과 <b>별도 클래스</b>인 이유는 이쪽만 {@code DataSource} 를 요구하기 때문이다.
 * {@link SchedulingConfig} 에 합치면 데이터소스 없이 도는 스케줄러 배선 테스트가 함께 깨진다.
 * 마스터 스위치는 같은 것을 쓴다 — 잡이 뜨는데 락 설정만 빠지면 {@code @SchedulerLock} 은 아무
 * 신호 없이 무시되고(프록시가 없으므로) 그게 정확히 이 변경이 막으려던 상태다.
 */
@Configuration
// 기본값은 세 잡의 명시값 중 <b>가장 긴</b> 것에 맞춘다. 이 값이 실제로 쓰이는 건 lockAtMostFor 를
// 빠뜨린 잡이 생겼을 때뿐인데, 그때 짧으면 회차가 리스를 넘겨 조용히 겹치고(= 락을 건 이유가 사라진다)
// 길면 인계만 늦는다. 실수의 대가가 작은 쪽으로 기울인다. 빠뜨림 자체는 SchedulerWiringTest 가 잡는다.
@EnableSchedulerLock(defaultLockAtMostFor = "PT50M")
@ConditionalOnProperty(prefix = "live.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileLockConfig {

    /**
     * 락 테이블. <b>단일 출처다</b> — 마이그레이션(V*__shedlock.sql)·프로바이더 설정·부팅 가드가 모두
     * 이 값을 봐야 한다. 복제해 두면 한쪽만 바뀌었을 때 가드는 통과하고 회차마다 예외가 나는,
     * 가장 알아채기 어려운 조합이 된다.
     */
    public static final String LOCK_TABLE = "live_schema.shedlock";

    /** {@code lock-at-least-for} 기본값. {@code @SchedulerLock} 과 이 가드가 같은 값을 봐야 한다. */
    public static final String LOCK_AT_LEAST_FOR = "PT1M";

    /** 잡별 리스 기본값. {@code @SchedulerLock} placeholder 와 같은 상수를 본다. */
    private static final Map<String, String> DEFAULT_LEASES = Map.of(
            "expire-ready", LiveReconcileProperties.ExpireReady.DEFAULT_LOCK_AT_MOST_FOR,
            "end-stale-live", LiveReconcileProperties.EndStaleLive.DEFAULT_LOCK_AT_MOST_FOR,
            "orphan-media", LiveReconcileProperties.OrphanMedia.DEFAULT_LOCK_AT_MOST_FOR);

    /** cron 전개 상한. 하루치를 보기 전에 끝나는 게 정상이고, 이건 극단값 방어다. */
    private static final int MAX_CRON_EXPANSIONS = 5000;

    /**
     * {@code lock-at-least-for} 가 어떤 잡의 cron 주기보다 길면 <b>부팅을 실패시킨다</b>.
     *
     * <p>이 값은 회차가 순식간에 끝나도 락을 붙잡고 있으라는 지시다. 주기보다 길면 다음 tick 이 아직
     * 살아 있는 락에 막혀 <b>조용히 건너뛴다</b> — 잡이 절반만 도는데 로그도 지표도 없다(락을 못 잡은
     * 회차는 카운터를 올리지 않는다). 지금까지 이걸 막는 건 예제 yaml 의 주석 한 줄뿐이었고, 값은
     * 환경변수로 덮이므로 그 주석은 배포 시점에 아무것도 강제하지 못한다.
     *
     * <p>주기는 cron 을 <b>하루치 전개해 그중 가장 짧은 간격</b>으로 잰다. 두 번만 전개하면 안 된다 —
     * {@code 0/N} 형태는 시 경계에서 간격이 줄어들기 때문이다(실측: {@code 0 0/7 * * * *} 은 첫 간격이
     * 7분이지만 00:56 → 01:00 구간이 4분이다). 첫 간격만 보면 {@code PT5M} 이 통과하고 시 경계 회차만
     * 조용히 스킵되는데, 그게 정확히 이 가드가 막으려던 상태다.
     *
     * <p>상한을 주기 자체가 아니라 <b>주기의 절반</b>으로 두는 이유: 주기와 같기 직전까지 허용하면
     * 다음 tick 이 락을 잡을 창이 몇 초로 줄어든다(10분 주기에 {@code PT9M} 이면 1분). 스레드 풀이 3개인데
     * 잡도 3개라 앞 회차가 LiveKit 왕복 중이면 tick 이 큐에서 밀릴 수 있고, 밀린 만큼 그 회차는 조용히
     * 사라진다. 절반이면 지연을 한 번 먹어도 살아남는다.
     *
     * <p>잡을 내린 환경은 검사하지 않는다 — 안 도는 잡의 cron 때문에 부팅이 막히면 안 된다.
     */
    @Bean
    public LockIntervalGuard lockIntervalsMustFitInCron(
                @Value("${live.reconcile.lock-at-least-for:" + LOCK_AT_LEAST_FOR + "}") String lockAtLeastForValue,
            @Value("${live.reconcile.expire-ready.cron:" + SchedulingConfig.EXPIRE_READY_CRON + "}")
            String expireReadyCron,
            @Value("${live.reconcile.end-stale-live.cron:" + SchedulingConfig.END_STALE_LIVE_CRON + "}")
            String endStaleLiveCron,
            @Value("${live.reconcile.orphan-media.cron:" + SchedulingConfig.ORPHAN_MEDIA_CRON + "}")
            String orphanMediaCron,
            @Value("${live.reconcile.expire-ready.enabled:true}") boolean expireReadyEnabled,
            @Value("${live.reconcile.end-stale-live.enabled:true}") boolean endStaleLiveEnabled,
            @Value("${live.reconcile.orphan-media.enabled:true}") boolean orphanMediaEnabled) {

        // 문자열로 받아 직접 파싱한다 — Duration 파라미터로 두면 변환 서비스가 있는 컨텍스트에서만
        // 도는 빈이 되어, 배선 테스트가 이 가드를 확인하지 못한다.
        //
        // ISO-8601 만 받으면 안 된다: @SchedulerLock 은 ShedLock 의 StringToDurationConverter 를 쓰고
        // 그쪽은 "1m" 같은 짧은 형식도 받는다. 여기서 Duration.parse 만 쓰면 ShedLock 이 정상 처리하는
        // 값에 부팅이 막힌다 — 같은 yaml 이 threshold: 60m, grace: 15m 로 이미 짧은 형식을 쓰고 있어
        // 운영자가 그렇게 적는 게 자연스럽다. DurationStyle 은 두 형식을 모두 받아 ShedLock 과 같은
        // 값 집합을 커버한다.
        Duration lockAtLeastFor = parseDuration("live.reconcile.lock-at-least-for", lockAtLeastForValue);

        Map<String, String> crons = new LinkedHashMap<>();
        if (expireReadyEnabled) {
            crons.put("expire-ready", expireReadyCron);
        }
        if (endStaleLiveEnabled) {
            crons.put("end-stale-live", endStaleLiveCron);
        }
        if (orphanMediaEnabled) {
            crons.put("orphan-media", orphanMediaCron);
        }

        crons.forEach((job, cron) -> {
            Duration period = shortestPeriod(job, cron);
            Duration allowed = period.dividedBy(2);
            if (lockAtLeastFor.compareTo(allowed) > 0) {
                throw new IllegalStateException(
                        "live.reconcile.lock-at-least-for(" + lockAtLeastFor + ") 가 " + job
                                + " 의 최단 실행 주기(" + period + ")의 절반(" + allowed + ")을 넘는다 — 다음"
                                + " 회차가 아직 풀리지 않은 락에 막혀 조용히 건너뛸 수 있다(로그도 지표도"
                                + " 남지 않는다). 주기를 늘리거나 lock-at-least-for 를 줄일 것.");
            }
        });
        return new LockIntervalGuard();
    }

    private static Duration parseDuration(String key, String value) {
        try {
            return DurationStyle.detectAndParse(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    key + " 를 기간으로 읽을 수 없다 — 값=" + value
                            + " (예: PT1M, 1m, 30s). @SchedulerLock 이 받는 형식과 같다.", e);
        }
    }

    /** cron 을 하루치 전개해 <b>가장 짧은</b> 실행 간격을 찾는다. 첫 간격이 최소라는 보장이 없다. */
    private static Duration shortestPeriod(String job, String cron) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            // 친절한 실패가 목적이다 — @Scheduled 가 나중에 죽으면 어느 잡의 cron 이 문제인지 안 보인다.
            throw new IllegalStateException(job + " 의 cron 표현식이 잘못됐다 — 값=" + cron, e);
        }
        // 하루 + 1시간을 보면 시·일 경계에서 줄어드는 간격이 모두 들어온다. 반복 상한은 1초 cron 같은
        // 극단값에서 부팅이 멈추지 않게 하는 안전장치다(그런 값이면 어차피 최소 간격이 즉시 잡힌다).
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime until = from.plusHours(25);

        LocalDateTime current = expression.next(from);
        if (current == null) {
            throw new IllegalStateException(job + " 의 cron 이 한 번도 실행되지 않는다 — 값=" + cron);
        }
        Duration shortest = null;
        for (int i = 0; i < MAX_CRON_EXPANSIONS; i++) {
            LocalDateTime next = expression.next(current);
            if (next == null) {
                break;
            }
            Duration gap = Duration.between(current, next);
            if (shortest == null || gap.compareTo(shortest) < 0) {
                shortest = gap;
            }
            if (next.isAfter(until)) {
                break;
            }
            current = next;
        }
        if (shortest == null) {
            throw new IllegalStateException(job + " 의 cron 이 두 번 이상 실행되지 않는다 — 값=" + cron);
        }
        return shortest;
    }

    /**
     * 잡별 {@code lock-at-most-for} 가 그 잡의 cron 주기보다 짧으면 <b>부팅을 실패시킨다</b>.
     *
     * <p>리스가 주기보다 짧으면 다음 tick 이 늘 만료된 락을 보게 되어 상호배제가 사실상 없어진다 —
     * 파괴적 스윕이 레플리카 수만큼 겹쳐 도는데, 정리 포트가 실패를 삼켜 예외로도 드러나지 않고
     * {@code reconcileActed} 집계만 배로 부푼다. 그게 이 잡의 유일한 판독법을 무너뜨린다.
     *
     * <p>하한을 임의의 상수로 두지 않고 <b>주기에서 끌어오는</b> 이유는 {@link #lockIntervalsMustFitInCron}
     * 과 같다 — 의미 있는 하한은 "이 잡이 얼마나 자주 도는가" 에서만 나온다. 회차 예산이 리스의 4/5
     * 이므로 이 하한은 예산의 하한이기도 하다.
     */
    @Bean
    public LeaseGuard leasesMustOutlastTheirCronPeriod(
            Environment environment,
            @Value("${live.reconcile.expire-ready.cron:" + SchedulingConfig.EXPIRE_READY_CRON + "}")
            String expireReadyCron,
            @Value("${live.reconcile.end-stale-live.cron:" + SchedulingConfig.END_STALE_LIVE_CRON + "}")
            String endStaleLiveCron,
            @Value("${live.reconcile.orphan-media.cron:" + SchedulingConfig.ORPHAN_MEDIA_CRON + "}")
            String orphanMediaCron,
            @Value("${live.reconcile.expire-ready.enabled:true}") boolean expireReadyEnabled,
            @Value("${live.reconcile.end-stale-live.enabled:true}") boolean endStaleLiveEnabled,
            @Value("${live.reconcile.orphan-media.enabled:true}") boolean orphanMediaEnabled) {

        Map<String, String> crons = new LinkedHashMap<>();
        if (expireReadyEnabled) {
            crons.put("expire-ready", expireReadyCron);
        }
        if (endStaleLiveEnabled) {
            crons.put("end-stale-live", endStaleLiveCron);
        }
        if (orphanMediaEnabled) {
            crons.put("orphan-media", orphanMediaCron);
        }

        crons.forEach((job, cron) -> {
            String key = "live.reconcile." + job + ".lock-at-most-for";
            // 미설정이면 잡별 기본 상수로 <b>같은 비교를 태운다</b>. 건너뛰면 안 된다 — cron 은 같은
            // 메서드가 설정에서 읽으므로, 리스는 기본값(20m)인데 cron 만 0 0/30 으로 늘린 배포가
            // 이 가드가 막으려던 상태 그대로 부팅한다.
            String configured = environment.getProperty(key);
            Duration lease = parseDuration(key,
                    configured != null ? configured : DEFAULT_LEASES.get(job));
            Duration period = shortestPeriod(job, cron);
            if (lease.compareTo(period) < 0) {
                throw new IllegalStateException(
                        key + "(" + lease + ") 가 " + job + " 의 최단 실행 주기(" + period
                                + ")보다 짧다 — 다음 tick 이 늘 만료된 락을 보게 되어 상호배제가"
                                + " 사라진다(파괴적 스윕이 레플리카 수만큼 겹쳐 돈다).");
            }
        });
        return new LeaseGuard();
    }

    /** 부팅 시점에만 의미가 있는 표식. {@code LiveSecurityConfig.ManagementPortGuard} 와 같은 모양이다. */
    public static final class LockIntervalGuard {
    }

    /** 부팅 시점에만 의미가 있는 표식. */
    public static final class LeaseGuard {
    }


    @Bean
    public TrackingLockProvider lockProvider(DataSource dataSource, LiveMetrics liveMetrics) {
        LockProvider jdbc = new JdbcTemplateLockProvider(lockConfiguration(dataSource));
        return new TrackingLockProvider(jdbc, liveMetrics);
    }

    /**
     * 별도 메서드인 이유는 <b>테스트가 값을 볼 수 있게</b> 하기 위해서다. {@code JdbcTemplateLockProvider}
     * 는 자기 설정을 노출하지 않아, 빈만 만들면 테이블명 오타나 {@code usingDbTime()} 누락을 Docker 없이는
     * 확인할 방법이 없다 — 그리고 그걸 확인하는 유일한 테스트는 CI 에서 빠진다({@code @Tag("docker")}).
     * 테스트가 다른 패키지({@code liveapp.scheduler})에 있어 {@code public} 이다.
     */
    public static JdbcTemplateLockProvider.Configuration lockConfiguration(DataSource dataSource) {
        return JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(LOCK_TABLE)
                .usingDbTime()
                .build();
    }
}
