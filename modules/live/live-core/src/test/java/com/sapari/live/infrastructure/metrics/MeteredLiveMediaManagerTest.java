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
