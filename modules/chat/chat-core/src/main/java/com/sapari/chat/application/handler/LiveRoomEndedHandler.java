package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.LiveRoomEndedSource;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.domain.repository.ChatSessionRepository;

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
 *
 * <p><b>마지막에 방 세션 키를 지운다</b>: 평시엔 세션마다 unregister가 필드를 하나씩 빼 키가 저절로 사라지지만,
 * Pod가 죽으면 그 Pod의 항목이 남아 activeCount를 부풀린 채 방치된다. 삭제·이후 HDEL 모두 멱등이라
 * 모든 Pod가 불러도, 뒤늦은 unregister가 삭제 뒤에 도착해도 안전하다(없는 키 HDEL은 무동작).
 * 종료 알림 다음에 두는 건 순서 보장이 필요해서가 아니라, 알림·닫기를 마친 뒤 정리하는 흐름이 읽기 쉬워서다.
 *
 * <p>강퇴 SET {@code kicked:{roomId}}는 여기서 건드리지 않는다 — chat은 읽기만 하고, 쓰기(SADD)는 api-app
 * 강퇴 기능의 몫이라 수명도 거기서 함께 정한다. 그 기능이 붙기 전까지는 삭제 주체가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveRoomEndedHandler {

    private final LiveRoomEndedSource source;
    private final SystemMessageService systemMessageService;
    private final ChatSessionManager sessionManager;
    private final ChatSessionRepository sessionRepository;

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

    /** 로컬 세션에 SYSTEM(ROOM_ENDED) 렌더 → close → 방 세션 키 정리(멱등, 순서 무관). (테스트 진입점) */
    Mono<Void> onRoomEnded(UUID roomId) {
        return systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED)
                .then(sessionManager.closeAll(roomId))
                .then(sessionRepository.clearRoom(roomId));
    }
}
