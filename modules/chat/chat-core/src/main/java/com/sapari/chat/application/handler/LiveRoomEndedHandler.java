package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.LiveRoomEndedSource;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;
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
 * <p><b>강퇴 SET {@code kicked:{roomId}}에는 만료를 붙인다</b>: 키 자체는 chat 소유인데 평시엔 TTL이
 * 없어(방송 도중 만료되면 강퇴자가 되돌아온다) 방이 끝날 때 회수하지 않으면 방마다 하나씩 영구히 남는다.
 * 지우지 않고 만료시키는 건 이 핸들러를 깨우는 근거가 진위를 확인할 수 없는 신호 한 건이기 때문이다 —
 * 즉시 삭제하면 잘못된 신호 하나로 그 방의 집행 상태가 그 자리에서 사라진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveRoomEndedHandler {

    private final LiveRoomEndedSource source;
    private final SystemMessageService systemMessageService;
    private final ChatSessionManager sessionManager;
    private final ChatSessionRepository sessionRepository;
    private final ChatRoomEndedRepository roomEndedRepository;
    private final ChatKickRepository kickRepository;

    private Disposable subscription;

    @PostConstruct
    void start() {
        subscription = source.ended()
                // defer로 감싼다 — onRoomEnded 안의 각 단계 표현식은 조립 시점에 즉시 평가되므로, 그중 하나가
                // 동기 throw하면 Mono를 돌려주기 전에 터져 아래 onErrorResume이 못 잡는다. 그러면 이 Pod의
                // 구독이 죽어 이후 모든 방 종료를 영구히 놓친다(재시작 전까지 세션도 안 닫힌다).
                .flatMap(roomId -> Mono.defer(() -> onRoomEnded(roomId))
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

    /**
     * 종료 마커 → SYSTEM(ROOM_ENDED) 렌더 → close → 방 세션 키 정리 → 강퇴 명단 만료. (테스트 진입점)
     *
     * <p><b>각 단계가 서로의 실패에 묶이지 않는다.</b> 그냥 이어 붙이면 앞이 터졌을 때 뒤가 통째로 안 돈다 —
     * 알림 전송 하나가 실패했다고 <b>세션이 안 닫히는</b> 것이 이 체인에서 가장 나쁜 결과다. 각 단계는
     * 실패해도 자기 몫만 잃고, 나머지는 그대로 진행한다.
     *
     * <p>마커를 가장 먼저 쓰는 이유는 순서 자체가 구멍 크기를 정하기 때문이다 — 세션을 닫은 뒤에 쓰면
     * 그 사이에 방금 끊긴 사용자가 아직 유효한 토큰으로 다시 들어온다.
     */
    Mono<Void> onRoomEnded(UUID roomId) {
        return keepGoing(roomEndedRepository.markEnded(roomId), roomId,
                        "종료 마커 기록 실패 — 이 방의 종료 판정이 전 세션에서 무력화된다(재입장·전송 모두)", true)
                .then(keepGoing(systemMessageService.renderToRoom(roomId, SystemMessageCode.ROOM_ENDED), roomId,
                        "종료 알림 전송 실패 — 클라는 사유 없이 끊긴다", true))
                .then(keepGoing(sessionManager.closeAll(roomId), roomId,
                        "세션 종료 실패 — 그 방 세션이 남는다", true))
                .then(keepGoing(sessionRepository.clearRoom(roomId), roomId,
                        "세션 키 정리 실패 — 키 TTL이 받는다", false))
                // 이 단계만 실패하면 그 방의 SET이 만료 없이 남는다(데이터는 온전하다).
                .then(keepGoing(kickRepository.expireAfterRoomEnded(roomId), roomId,
                        "강퇴 명단 만료 부여 실패 — 그 방 SET이 회수되지 않는다", false));
    }

    /**
     * 실패를 삼키지 않고 무엇을 잃었는지 남긴 뒤, 남은 단계를 계속 진행시킨다.
     *
     * <p>등급은 <b>무엇을 잃었는지</b>로 정한다 — 어떤 종류의 오류인지가 아니다. 통제(종료 판정)를
     * 잃는 실패는 ERROR고, 스스로 복구되는 것(TTL이 받아주는 정리)은 WARN이다. 원인 종류로 등급을
     * 매기면 Redis 장애로 통제가 열린 순간이 WARN에 묻혀 ERROR만 보는 사람에게 안 보인다.
     *
     * <p>이 체인은 방송 종료마다 한 번씩만 돌아 로그 폭주 위험이 없으므로 예외를 통째로 싣는다
     * (건당 호출되는 전송 경로와 다르다).
     */
    private Mono<Void> keepGoing(Mono<Void> step, UUID roomId, String whatIsLost, boolean losesControl) {
        return step.onErrorResume(e -> {
            if (losesControl) {
                log.error("{} roomId={}", whatIsLost, roomId, e);
            } else {
                log.warn("{} roomId={} cause={}", whatIsLost, roomId, e.getClass().getSimpleName());
            }
            return Mono.empty();
        });
    }
}
