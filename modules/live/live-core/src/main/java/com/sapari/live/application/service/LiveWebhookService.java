package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.application.port.LiveWebhookHandler;
import com.sapari.live.application.port.WebhookVerifier;
import com.sapari.live.command.LiveWebhookCommand;
import com.sapari.live.port.ProcessLiveWebhookUseCase;

/**
 * LiveKit webhook 수신 서비스 — 검증 후 등록된 핸들러로 라우팅한다.
 *
 * <p>이 브랜치는 수신·검증·라우팅 골격만 제공한다. {@link LiveWebhookHandler} 구현은 별도 작업에서
 * 추가되며, 매칭되는 핸들러가 없으면 수신·검증만 하고 처리는 하지 않는다(no-op).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveWebhookService implements ProcessLiveWebhookUseCase {

    private final WebhookVerifier webhookVerifier;
    private final List<LiveWebhookHandler> handlers;

    @Override
    public void process(LiveWebhookCommand command) {
        // 서명 검증 실패는 verify가 InvalidWebhookException으로 거부 → 아래 라우팅에 도달하지 않는다.
        LiveWebhookEvent event = webhookVerifier.verify(command.body(), command.authHeader());

        boolean routed = false;
        for (LiveWebhookHandler handler : handlers) {
            if (handler.supports(event.type())) {
                routed = true;
                // 핸들러별로 예외를 격리한다 — 한 핸들러 실패가 다른 핸들러 실행이나 응답을 막지 않도록.
                // (실패를 위로 던지면 LiveKit이 webhook 전체를 재시도해 이미 성공한 핸들러가 중복 실행된다.)
                try {
                    handler.handle(event);
                } catch (RuntimeException e) {
                    log.error("LiveKit webhook 핸들러 처리 실패: type={}, room={}, handler={}",
                            event.type(), event.roomName(), handler.getClass().getSimpleName(), e);
                }
            }
        }

        if (!routed) {
            // 처리기가 없는 이벤트는 정상(관심 없는 이벤트이거나 아직 미구현) — 검증만 하고 넘어간다.
            log.debug("처리기 없는 LiveKit webhook 이벤트 수신: type={}, room={}", event.type(), event.roomName());
        }
    }
}
