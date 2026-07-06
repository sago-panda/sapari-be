package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.LiveRoomEndedSource;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 방 종료 처리 — {@link LiveRoomEndedSource}(live:room:ended) 구독 → 이 Pod 로컬 세션에 SYSTEM(ROOM_ENDED)
 * 렌더 후 close.
 *
 * <p>ROOM_ENDED는 방주인 구분 없이 전원 동일 신호라 {@link SystemMessageService#renderToRoom}(단일 메시지)로
 * 렌더하고, 그 다음 {@link ChatSessionManager#closeAll}로 로컬 세션을 닫는다(SYSTEM 도착 후 close 순서 보장).
 * 구독은 시작 시 1회(단일 채널) 걸고, 봉투 1건 처리 실패가 스트림을 죽이지 않게 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveRoomEndedHandler {

    private final LiveRoomEndedSource source;
    private final SystemMessageService systemMessageService;
    private final ChatSessionManager sessionManager;

    private Disposable subscription;

    @PostConstruct
    void start() {
        subscription = source.ended()
                .flatMap(roomId -> onRoomEnded(roomId)
                        .onErrorResume(e -> {
                            log.error("ROOM_ENDED 처리 실패 — skip(구독 유지) roomId={}", roomId, e);
                            return Mono.empty();
                        }))
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    /** 로컬 세션에 SYSTEM(ROOM_ENDED) 렌더 후 close. (테스트 진입점) */
    Mono<Void> onRoomEnded(UUID roomId) {
        return systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)
                .then(sessionManager.closeAll(roomId));
    }
}
