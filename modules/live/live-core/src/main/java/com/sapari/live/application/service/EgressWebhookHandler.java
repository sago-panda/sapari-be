package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.application.port.LiveWebhookHandler;
import com.sapari.live.domain.model.VodStatus;

/**
 * egress 웹훅 → 다시보기 처리 상태. 이벤트 종류가 아니라 egress <b>상태 값</b>으로 판정하고, 방 상태(Live/Ended)는
 * 보지 않는다 — 녹화 결과만이 근거다(방송 중 녹화가 먼저 끝나도 정상 종료면 녹화 완료).
 *
 * <p>되돌릴 수 없는 일을 하지 않는다: 상태를 앞으로만 옮기고(중복·역순은 저장소 WHERE 에서 0행) 미디어를 건드리지
 * 않으므로, 서명된 이벤트의 재전송이 있어도 이벤트 id 중복 제거 없이 안전하다.
 *
 * <p>매칭은 방이 저장한 {@code egress_id} — 720p 대표 하나뿐이다. 다른 ABR 화질의 이벤트는 0행이라 그 화질의
 * 실패는 여기서 보이지 않는다(publisher 빈을 등록하기 전에 다시 볼 것). {@code egress_started} 는 시작 트랜잭션이
 * egressId 를 커밋하기 전에 와서 0행일 수 있다 — NONE→READY 를 허용하므로 무해하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgressWebhookHandler implements LiveWebhookHandler {

    private static final Set<String> EVENT_TYPES = Set.of("egress_started", "egress_updated", "egress_ended");

    private final UpdateVodStatusService updateVodStatusService;

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPES.contains(eventType);
    }

    @Override
    public void handle(LiveWebhookEvent event) {
        if (event.egressId() == null || event.egressId().isBlank()) {
            log.warn("egress webhook 에 egressId 가 없어 스킵: type={}", event.type());
            return;
        }
        VodStatus target = targetOf(event.egressStatus());
        if (target == null) {
            log.warn("알 수 없는 egress 상태 — vod_status 를 바꾸지 않는다: egressId={}, status={}",
                    event.egressId(), event.egressStatus());
            return;
        }
        String reason = null;
        if (target == VodStatus.FAILED) {
            reason = event.egressError() == null || event.egressError().isBlank()
                    ? event.egressStatus() : event.egressError();
            log.warn("egress 실패 — 다시보기 FAILED: egressId={}, status={}", event.egressId(), event.egressStatus());
        }
        updateVodStatusService.advance(event.egressId(), target, reason);
    }

    /** LiveKit EgressStatus 이름 → 목표 상태. 한도 도달은 그 지점까지의 녹화가 남으므로 녹화 완료로 본다. */
    private static VodStatus targetOf(String egressStatus) {
        if (egressStatus == null) {
            return null;
        }
        return switch (egressStatus) {
            case "EGRESS_STARTING", "EGRESS_ACTIVE", "EGRESS_ENDING" -> VodStatus.PROCESSING;
            case "EGRESS_COMPLETE", "EGRESS_LIMIT_REACHED" -> VodStatus.READY;
            case "EGRESS_FAILED", "EGRESS_ABORTED" -> VodStatus.FAILED;
            default -> null;
        };
    }
}
