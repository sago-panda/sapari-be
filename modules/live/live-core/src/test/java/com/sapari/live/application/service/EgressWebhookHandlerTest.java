package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.domain.model.VodStatus;

@DisplayName("EgressWebhookHandler — egress 상태 값으로 다시보기 처리 상태를 정한다")
class EgressWebhookHandlerTest {

    private final UpdateVodStatusService service = mock(UpdateVodStatusService.class);
    private final EgressWebhookHandler handler = new EgressWebhookHandler(service);

    private static LiveWebhookEvent event(String egressId, String status, String error) {
        return new LiveWebhookEvent("egress_updated", "room", null, egressId, status, error);
    }

    @Test
    @DisplayName("egress 이벤트 세 종류만 supports")
    void supports() {
        assertThat(handler.supports("egress_started")).isTrue();
        assertThat(handler.supports("egress_updated")).isTrue();
        assertThat(handler.supports("egress_ended")).isTrue();
        assertThat(handler.supports("ingress_started")).isFalse();
        assertThat(handler.supports("room_finished")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "EGRESS_STARTING, PROCESSING",
            "EGRESS_ACTIVE, PROCESSING",
            "EGRESS_ENDING, PROCESSING",
            "EGRESS_COMPLETE, READY",
            "EGRESS_LIMIT_REACHED, READY"
    })
    @DisplayName("진행·정상 종료 상태는 사유 없이 넘긴다")
    void nonFailure(String egressStatus, VodStatus expected) {
        handler.handle(event("eg-1", egressStatus, null));

        then(service).should().advance("eg-1", expected, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EGRESS_FAILED", "EGRESS_ABORTED"})
    @DisplayName("실패·중단은 FAILED 와 egress 가 준 오류를 사유로 넘긴다")
    void failureWithError(String egressStatus) {
        handler.handle(event("eg-1", egressStatus, "pipeline crashed"));

        then(service).should().advance("eg-1", VodStatus.FAILED, "pipeline crashed");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("실패인데 오류가 비면 상태 이름을 사유로 남긴다 — 실패는 반드시 드러난다")
    void failureWithoutError(String error) {
        handler.handle(event("eg-1", "EGRESS_ABORTED", error));

        then(service).should().advance("eg-1", VodStatus.FAILED, "EGRESS_ABORTED");
    }

    @ParameterizedTest
    @CsvSource(value = {"eg-1, UNRECOGNIZED", "eg-1, NULL", "NULL, EGRESS_COMPLETE", "' ', EGRESS_COMPLETE"},
            nullValues = "NULL")
    @DisplayName("모르는 상태·상태 없음·egressId 없음은 갱신하지 않는다")
    void skips(String egressId, String egressStatus) {
        handler.handle(event(egressId, egressStatus, null));

        then(service).should(never()).advance(any(), any(), any());
    }
}
