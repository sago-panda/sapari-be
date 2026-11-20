package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.global.logging.LogFields;
import com.sapari.chat.application.port.AccountWithdrawingSource;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.SessionCutReason;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.domain.repository.ChatWithdrawalWriteRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 탈퇴 신청을 받아 이 Pod 에 열려 있는 그 사람의 세션을 끊는다.
 *
 * <p><b>이 핸들러가 닫는 구멍.</b> 전송 경로의 관문 일곱(진행중·입력검증·권한·강퇴·밴·레이트리밋·
 * 욕설)에 <b>계정 상태를 묻는 것이 없다</b>. 그리고 탈퇴 경로가 이미 access 토큰을 폐기하지만
 * ({@code revokeAllSessions}) <b>이미 수립된 WS 세션은 토큰을 다시 보지 않는다</b> — 폐기가 막는
 * 것은 재입장뿐이다. 그래서 탈퇴를 신청한 사람이 열려 있던 세션으로 계속 채팅한다.
 *
 * <p><b>그 폐기도 재입장을 다 막지 못한다.</b> 폐기가 막는 것은 새 룸 토큰의 발급이고, <b>이미 받아 둔</b>
 * 룸 토큰은 만료 전까지 핸드셰이크를 지난다. 그래서 끊기 전에 탈퇴를 기록하고
 * ({@link ChatWithdrawalWriteRepository}) 입장 게이트가 그 기록을 본다. 순서가 기록 → 끊기인 이유:
 * 반대로 하면 끊긴 직후의 재접속이 기록보다 먼저 게이트에 닿을 수 있다.
 *
 * <p><b>왜 {@link ChatAccountEventHandler} 에 분기를 더하지 않는가.</b> 그쪽 소스는 chat 이 자기
 * 채널에 발행한 봉투({@code ChatAccountEvent})를 받고, 이 사건은 user 가 자기 채널에 자기 모양으로
 * 발행한다. 두 스트림이 별개라 구독도 별개다 — 합치면 소스 하나의 끊김이 다른 쪽 조치까지 멈춘다.
 *
 * <p><b>구독이 죽으면 그 Pod 는 재시작 전까지 모든 탈퇴를 놓친다.</b> 그래서 이벤트 하나의 실패가
 * 스트림을 죽이지 못하게 한다. {@code Mono.defer} 로 감싸는 이유는 처리 안의 표현식들이
 * <b>조립 시점에</b> 평가되기 때문이다 — 그중 하나가 동기 throw 하면 {@code Mono} 를 돌려주기 전에
 * 터져 {@code onErrorResume} 이 잡지 못한다(형제 핸들러가 같은 처방을 쓴다).
 *
 * <p>⚠️ <b>놓친 발행을 메우는 경로가 없다.</b> 발행은 at-most-once 이고(user 쪽 어댑터가 실패를
 * 삼킨다) 재조정이 뒤늦게 정리해 주지도 않는다. 놓치는 길은 둘이다 — 발행 자체의 실패와,
 * <b>구독이 끊겨 있던 동안의 발행</b>(Redis Pub/Sub 은 보존이 없어 그 창의 메시지는 사라진다).
 *
 * <p>놓쳤을 때의 상한은 <b>축마다 다르다.</b>
 * <ul>
 *   <li><b>보내는 쪽</b>은 상한이 선다. {@code EntryGate.isRoomAlive} 가 전송 경로에서 창 간격으로
 *       종료 마커(와 live 의 종료 키)를 다시 읽고, 끝난 방이면 {@code LiveNotActiveException} → 그 세션이 닫힌다
 *       ({@code ChatWebSocketHandler.terminateRoomEnded}). 이 기구는 <b>신호에 의존하지 않는다</b>
 *       — 지속 마커를 직접 읽으므로 구독이 죽어 있어도 돈다. 그래서 상한이 창 길이 + 다음 전송이다.
 *   <li><b>읽기만 하는 쪽</b>은 상한이 그렇게 서지 않는다. 그 재확인이 전송 경로에만 있어
 *       ({@code ChatWebSocketHandler} 가 "같은 Pod 의 수신 전용 세션은 각자 보낼 때까지 닫히지
 *       않는다" 고 적는다) 탈퇴자가 더 말하지 않고 보기만 하면 걸리지 않는다. 남는 기구는
 *       방 종료 정리인데 그것도 <b>같은 구독</b>을 탄다({@code ClearRoomEndedMarkerService} 가
 *       "markEnded 호출처는 {@code LiveRoomEndedHandler} 하나 — live:room:ended 구독으로만
 *       깨어난다" 고 적는다). 그 Pod 의 구독이 죽어 있었다면 그쪽도 안 오므로, 상한은
 *       <b>세션 수명</b>({@code chat.session.max-lifetime}, 기본 60분)이다 — {@code ChatWebSocketHandler}
 *       가 그때 모든 세션의 종료를 조건 없이 촉발하고(막힌 클라도 유예 3s + flush 상한 5s 안에 닫힌다),
 *       재접속은 live 의 입장을 다시 탄다.
 * </ul>
 *
 * <p>⚠️ 한때 이 자리에 "방 종료가 세션을 정리하므로 상한은 그 방송의 남은 길이다" 라고만 적었다.
 * 결론은 맞았지만 근거로 든 기구가 <b>이 사건을 놓치게 만든 바로 그 구독</b>에 의존해서, 문장이
 * 자기가 한정하려는 것에 근거를 두고 있었다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountWithdrawingHandler {

    private final AccountWithdrawingSource source;
    private final ChatSessionManager sessionManager;
    private final ChatWithdrawalWriteRepository withdrawalWriteRepository;
    private final ChatMetrics metrics;

    private Disposable subscription;

    @PostConstruct
    void start() {
        subscription = source.withdrawing()
                .flatMap(userId -> Mono.defer(() -> onWithdrawing(userId))
                        .onErrorResume(e -> {
                            log.error("탈퇴 신청 처리 실패 — skip(구독 유지) userId={}", userId, e);
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

    /**
     * 탈퇴 기록을 남기고, 사유를 보내고, 성공하든 실패하든 끊는다. (테스트 진입점)
     *
     * <p>기록이 끊기보다 앞선다. 끊긴 클라이언트는 곧바로 재접속하는데, 그 사람이 탈퇴 전에 받아 둔 룸
     * 토큰은 아직 유효하다 — 입장 관문이 그것을 막는 근거가 이 기록이라, 끊고 나서 쓰면 재접속이 기록보다
     * 먼저 관문에 닿을 수 있다.
     *
     * <p>사유와 끊기의 순서도 계약이다. 먼저 끊으면 sink 가 닫혀 뒤이은 사유 프레임이 조용히 사라지고, 다른 탭에서
     * 보고 있던 본인은 <b>아무것도 누르지 않았는데</b> 끊긴 이유를 모른다 — 강퇴·밴 경로가 같은
     * 이유로 같은 순서를 쓴다.
     *
     * <p>사유 전송 실패가 종료를 막지 않는다. 알림 하나 때문에 <b>세션이 안 닫히는</b> 것이 여기서
     * 가장 나쁜 결과다 — 탈퇴한 사람이 그대로 남는다.
     *
     * <p>이 Pod 에 그 사람의 세션이 없으면 사유와 끊기는 아무 일도 하지 않는다. 대부분의 Pod 가 그 경우다.
     * 기록은 그런 Pod 도 쓴다 — 같은 키·같은 값이라 여러 번 써도 하나다.
     */
    Mono<Void> onWithdrawing(UUID userId) {
        OutboundMessage withdrawing = OutboundMessage.system(SystemMessageCode.WITHDRAWING);
        // 기록이 실패해도 끊기는 그대로 간다 — 열려 있는 세션을 닫는 것이 이 핸들러의 본업이고, 기록을
        // 못 남긴 대가(남은 토큰으로의 재입장)는 끊지 않는 대가(열린 세션이 계속 산다)보다 작다. 모든
        // Pod 가 같은 이벤트로 같은 기록을 쓰므로 한 Pod 에만 난 실패는 다른 Pod 의 쓰기가 덮는다(Redis
        // 자체가 내려가면 전부 실패하고, 그때 남는 창이 남은 룸 토큰 수명이다).
        return withdrawalWriteRepository.markWithdrawing(userId)
                .onErrorResume(e -> {
                    // 로그를 솎지 않는 이유: 탈퇴 한 건에 Pod 마다 한 번 돈다(건당 호출되는 전송 경로와 다르다).
                    metrics.withdrawalRecordFailed();
                    log.error("탈퇴 기록 실패 — 남은 룸 토큰으로 재입장할 수 있다. 끊기는 그대로 진행한다", e);
                    return Mono.empty();
                })
                .then(sessionManager.sendToUserEverywhere(userId, withdrawing))
                .onErrorResume(e -> {
                    log.warn("탈퇴 사유 전송 실패 — 종료는 그대로 진행한다", e);
                    return Mono.empty();
                })
                .then(sessionManager.closeUserEverywhere(userId, SessionCutReason.WITHDRAWN))
                // 구독 스레드라 세션 컨텍스트가 없다. 탈퇴는 사람 단위라 방을 모르므로 대상을
                // 메시지에 넣는 대신 필드로 싣는다 — "이 사람에게 일어난 모든 줄" 로 묶이는 축이다.
                .contextWrite(context -> context.put(LogFields.USER_ID, userId.toString()));
    }
}
