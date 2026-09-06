package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatAccountEventSource;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatAccountEvent;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 계정 조치를 받아 이 Pod에 열려 있는 그 사람의 세션을 끊는다.
 *
 * <p><b>이 핸들러가 닫는 구멍.</b> 입장 게이트는 새 접속만 막고, 전송 경로의 밴 검사는 그 사람이 <b>말할
 * 때</b>만 돈다. 밴이 걸릴 때 다른 방에 열려 있던 세션은 아무 말도 하지 않으면 그대로 살아 있다 —
 * 방송을 계속 보고, 방 주인 세션이면 원문과 이메일도 계속 받는다.
 *
 * <p><b>구독이 죽으면 그 Pod는 재시작 전까지 모든 계정 조치를 놓친다.</b> 그래서 이벤트 하나의 실패가
 * 스트림을 죽이지 못하게 한다. {@code Mono.defer}로 감싸는 이유는 처리 안의 표현식들이 <b>조립 시점에</b>
 * 평가되기 때문이다 — 그중 하나가 동기 throw하면 {@code Mono}를 돌려주기 전에 터져 아래
 * {@code onErrorResume}이 잡지 못한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAccountEventHandler {

    private final ChatAccountEventSource source;
    private final ChatSessionManager sessionManager;

    private Disposable subscription;

    @PostConstruct
    void start() {
        subscription = source.events()
                .flatMap(event -> Mono.defer(() -> onEvent(event))
                        .onErrorResume(e -> {
                            log.error("계정 이벤트 처리 실패 — skip(구독 유지) kind={}",
                                    event.getClass().getSimpleName(), e);
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

    /** 종류별 처리. (테스트 진입점) */
    Mono<Void> onEvent(ChatAccountEvent event) {
        return switch (event) {
            case ChatAccountEvent.Banned banned -> onBanned(banned.userId());
        };
    }

    /**
     * 사유를 먼저 보내고, 성공하든 실패하든 끊는다.
     *
     * <p>순서가 계약이다. 먼저 끊으면 sink가 닫혀 뒤이은 사유 프레임이 조용히 사라지고, 클라이언트는
     * 1008만 받은 채 <b>왜</b> 끊겼는지 모른다 — 강퇴 경로가 같은 이유로 같은 순서를 쓴다.
     *
     * <p>사유 전송 실패가 종료를 막지 않는다. 알림 하나 때문에 <b>세션이 안 닫히는</b> 것이 여기서 가장
     * 나쁜 결과다 — 밴이 걸린 사람이 그대로 남는다.
     *
     * <p>이 Pod에 그 사람의 세션이 없으면 둘 다 아무 일도 하지 않는다. 대부분의 Pod가 그 경우다.
     */
    private Mono<Void> onBanned(UUID userId) {
        OutboundMessage banned = OutboundMessage.system(SystemMessageCode.BANNED);
        return sessionManager.sendToUserEverywhere(userId, banned)
                .onErrorResume(e -> {
                    log.warn("밴 사유 전송 실패 — 종료는 그대로 진행한다", e);
                    return Mono.empty();
                })
                .then(sessionManager.closeUserEverywhere(userId));
    }
}
