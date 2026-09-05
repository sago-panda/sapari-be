package com.sapari.liveapp.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 테이블이 남으므로 "지금 누가 쥐고 있나"를 조회로 확인할 수 있다는 점도 운영상 크다.
 *
 * <p>{@code usingDbTime()} — 만료 판정을 DB {@code now()} 로 한다. 인스턴스 시계가 서로 어긋나도
 * 상호배제가 깨지지 않는다.
 *
 * <p><b>락 유지 시간</b>({@code live.reconcile.<job>.lock-at-most-for}) 은 곧 <b>락 보유 인스턴스가
 * 죽었을 때의 인계 지연</b>이다. ShedLock 은 실행 중인 회차를 중단시키지도 리스를 연장하지도 않으므로,
 * 회차가 이 값을 넘기면 락이 만료된 채로 계속 돌고 다음 tick 의 다른 인스턴스가 같은 회차를 겹쳐 돈다
 * — 즉 <b>짧게 주면 락을 건 이유가 사라진다</b>. 그래서 잡의 최악 실행 시간보다 길게 잡는다(잡별 근거는
 * 각 스케줄러). 프로퍼티로 뺀 건 인계 테스트가 이 값을 초 단위로 줄여야 하기 때문이다.
 *
 * <p>{@code live.reconcile.lock-at-least-for} 는 회차가 순식간에 끝났을 때를 막는다. 두 인스턴스의
 * cron 발화가 지터로 수백 ms 어긋나면 먼저 돈 쪽이 이미 락을 놓은 뒤라 뒤에 온 쪽이 같은 회차를 한 번
 * 더 돈다. <b>cron 간격보다 짧게 유지할 것</b> — 주기를 이 값 아래로 내리면 회차를 조용히 건너뛴다.
 *
 * <p><b>관측 사각지대</b>(도입으로 새로 생겼다): {@code @SchedulerLock} 은 {@code run()} <b>바깥</b>을
 * 감싸므로, 락을 못 잡은 회차는 {@code reconcileRound*} 카운터를 하나도 올리지 않는다. 레플리카 합산은
 * 회차당 1이라 정상이지만, 락 홀더가 죽어 행이 남으면 그 잡은 유지 시간만큼 <b>기록이 0</b>이 되어
 * "스케줄러가 죽었다"와 구분되지 않는다.
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
 * <p>다만 <b>어느 쪽이든 {@code reconcileRoundFailed} 는 오르지 않는다.</b> 프록시가 유스케이스 바깥이라
 * 예외가 스케줄러의 {@code catch} 에도 닿지 않아, 준비해 둔 도메인 문구 대신 Spring 기본 핸들러 로그만
 * 남는다(로그는 남으므로 "무기록"은 아니다). 지표까지 메우려면 락 결과를 세는 {@code LiveMetrics} 포트
 * 메서드가 필요하고, 그건 SPR-142 범위 밖이다.
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
@EnableSchedulerLock(defaultLockAtMostFor = "PT90M")
@ConditionalOnProperty(prefix = "live.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(lockConfiguration(dataSource));
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
                .withTableName("live_schema.shedlock")
                .usingDbTime()
                .build();
    }
}
