package com.sapari.live.infrastructure.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileJob;

/**
 * <b>실제 스크레이프 출력</b>으로 지표를 검증한다. 나머지 테스트가 쓰는 {@code SimpleMeterRegistry} 로는
 * 잡히지 않는 결함이 여기서만 드러나기 때문이다.
 *
 * <p>프로메테우스 레지스트리는 <b>같은 이름의 미터가 모두 같은 태그 키 집합을 갖도록 요구</b>한다.
 * 어기면 예외가 아니라 <b>WARN 한 줄 뒤 조용한 등록 실패</b>이고(그 뒤로는 debug 로 내려가 로그도 없다),
 * 결과적으로 나중에 등록되는 시계열이 통째로 사라진다. {@code SimpleMeterRegistry} 는 이 제약이 없어
 * 단위 테스트는 전부 통과한다 — 운영에서만, 그것도 조용히 실패한다.
 *
 * <p>실제로 이 저장소에서 그 일이 있었다: 완료/실패에는 {@code reason} 이 없고 중단에만 있어서
 * <b>중단 시계열이 스크레이프에 나오지 않았다.</b> 하필 그 지표는 "가드가 회차를 삼켰다는 유일한 외부
 * 신호" 다. 그래서 이 테스트가 존재한다 — 지표를 하나 추가할 때마다 여기에 한 줄씩 늘릴 것.
 */
class PrometheusScrapeTest {

    private PrometheusMeterRegistry registry;
    private LiveMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new MicrometerLiveMetrics(registry);
    }

    @Test
    @DisplayName("회차의 세 결과가 모두 스크레이프에 나온다 — 태그 키 집합이 어긋나면 나중 것이 조용히 사라진다")
    void allThreeOutcomes_appearInScrape() {
        metrics.reconcileRoundCompleted(ReconcileJob.END_STALE_LIVE, Duration.ofSeconds(1));
        metrics.reconcileRoundAborted(
                ReconcileJob.END_STALE_LIVE, ReconcileAbortReason.NO_ACTIVE_EGRESS_CLUSTER_WIDE);
        metrics.reconcileRoundFailed(ReconcileJob.END_STALE_LIVE);

        String scrape = registry.scrape();

        assertThat(scrape).contains("outcome=\"completed\"");
        assertThat(scrape).contains("outcome=\"aborted\"");
        assertThat(scrape).contains("outcome=\"failed\"");
        // 중단 사유가 살아 있어야 한다 — 이게 오설정(200+빈 목록)을 가리키는 값이다
        assertThat(scrape).contains("reason=\"no_active_egress_cluster_wide\"");
    }

    @Test
    @DisplayName("등록 실패가 없었음을 미터 개수로 확인한다 — 조용히 버려지면 개수가 줄어든다")
    void noMeterIsSilentlyDropped() {
        metrics.reconcileRoundCompleted(ReconcileJob.EXPIRE_READY, Duration.ofSeconds(1));
        metrics.reconcileRoundAborted(
                ReconcileJob.EXPIRE_READY, ReconcileAbortReason.NO_ACTIVE_EGRESS_CLUSTER_WIDE);

        long rounds = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("live.reconcile.round"))
                .count();

        assertThat(rounds).isEqualTo(2);
    }
}
