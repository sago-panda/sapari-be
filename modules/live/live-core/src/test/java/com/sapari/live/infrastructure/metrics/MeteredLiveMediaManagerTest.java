package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.domain.exception.LiveMediaException;

class MeteredLiveMediaManagerTest {

    private final LiveMediaManager delegate = mock(LiveMediaManager.class);
    private MeterRegistry registry;
    private MeteredLiveMediaManager metered;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metered = new MeteredLiveMediaManager(delegate, registry);
    }

    @Test
    @DisplayName("전역 스윕 실패는 삼키지 않고 그대로 던진다 — 빈 목록으로 바뀌면 정리 잡의 오설정 가드가 무력화된다")
    void globalSweepFailure_isRethrown() {
        willThrow(new LiveMediaException("조회 실패")).given(delegate).listAllEgress();

        assertThatThrownBy(() -> metered.listAllEgress())
                .isInstanceOf(LiveMediaException.class);
    }

    /**
     * 신설 포트. 정리 계열이라 {@code LiveKitMediaManager} 쪽은 실패를 삼키지만, <b>계측 래퍼는
     * 삼키면 안 된다</b>(AGENTS "It must never swallow") — 여기서 삼키면 어댑터가 바뀌었을 때
     * 실패가 지표에도 예외에도 안 남는다.
     */
    @Test
    @DisplayName("stopEgress 는 계측만 하고 위임한다 — 실패를 삼키지 않는다")
    void stopEgress_isMeteredAndNeverSwallows() {
        UUID roomId = UUID.randomUUID();

        metered.stopEgress(roomId, "eg-1");

        then(delegate).should().stopEgress(roomId, "eg-1");
        assertThat(registry.get("live.media.call")
                .tag("op", "stopEgress").tag("result", "success")
                .timer().count()).isEqualTo(1);

        willThrow(new LiveMediaException("중단 실패")).given(delegate).stopEgress(roomId, "eg-2");

        assertThatThrownBy(() -> metered.stopEgress(roomId, "eg-2"))
                .isInstanceOf(LiveMediaException.class);
        assertThat(registry.get("live.media.call")
                .tag("op", "stopEgress").tag("result", "failure")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패한 호출은 result=failure 로 센다")
    void failure_isTaggedFailure() {
        willThrow(new LiveMediaException("조회 실패")).given(delegate).listAllEgress();

        assertThatThrownBy(() -> metered.listAllEgress()).isInstanceOf(LiveMediaException.class);

        assertThat(registry.get("live.media.call")
                .tag("op", "listAllEgress").tag("result", "failure")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("성공한 호출은 반환값을 그대로 통과시키고 result=success 로 센다")
    void success_passesThroughAndIsTaggedSuccess() {
        List<EgressSummary> expected = List.of(new EgressSummary("eg-1", UUID.randomUUID().toString(), true, null));
        given(delegate.listAllEgress()).willReturn(expected);

        assertThat(metered.listAllEgress()).isSameAs(expected);
        assertThat(registry.get("live.media.call")
                .tag("op", "listAllEgress").tag("result", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 목록도 그대로 통과한다 — 계측이 '없음'을 '실패'로 바꾸지 않는다")
    void emptyList_passesThroughUnchanged() {
        given(delegate.publishingIngressIdsOrEmpty(any())).willReturn(List.of());

        assertThat(metered.publishingIngressIdsOrEmpty(UUID.randomUUID())).isEmpty();
    }

    private static UUID any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
