package com.sapari.streamingapp.websocket;

import org.springframework.stereotype.Component;

import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * chat 소유 입장 게이트 — 룸 토큰 검증({@code RoomTokenVerifier}) 통과 후 실행되는 모더레이션 검사.
 *
 * <p>토큰은 "라이브 여부 + 신원 + owner"까지 담지만 enter <i>이후</i>의 강퇴/밴은 못 담는다. 그건 chat 소유
 * 상태(Redis)라 핸드셰이크에서 여기서 검사한다.
 *
 * <p>검사는 셋이다 — <b>방 종료 마커</b>(게스트 포함 전원), <b>강퇴</b>(회원만), <b>플랫폼 밴</b>(회원만).
 * 강퇴는 이 방에서 나가는 것이고 밴은 어느 방에도 못 들어오는 것이라, 축이 달라 따로 본다.
 *
 * <p><b>둘 다 fail-open</b>: 조회 실패(Redis 장애) 시 입장을 <i>허용</i>한다(가용성 우선 — 채팅 전면 불능이
 * 강퇴자·종료방 일시 통과보다 나쁜 결과). 열린 구간은 사유별로 스로틀 로그 + 누적 통과 건수를 남긴다.
 *
 * <p>게스트(에페메랄 id)는 강퇴·밴 대상이 아니므로 건너뛴다 — 접속마다 id가 새로 발급돼 명단에 올려도
 * 다음 접속에서 다른 사람이 된다. 검사해봐야 항상 통과라 Redis 왕복만 늘어난다.
 *
 * <p><b>입장 이후에도 한 번 더 본다</b>({@link #isRoomAlive}): 종료 신호는 Pub/Sub(무영속)이라 그 순간
 * 구독이 끊겨 있던 Pod는 통째로 놓친다. 그 Pod의 세션은 닫히지 않고, 끝난 방에 계속 글을 쓴다.
 * 입장 때 한 번 본 것으로는 유계가 되지 않으므로 전송 경로에서 주기적으로 다시 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntryGate {

    /**
     * 게이트가 열린 사실을 남기는 간격. 이 코드베이스의 다른 fail-open 로그와 같은 규율이다 —
     * Redis가 죽는 순간이 곧 재접속 폭주 구간이라, 입장마다 찍으면 원인 로그를 제 로그로 묻는다.
     * 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다.
     */
    private static final long GATE_OPEN_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    /** 사유별로 따로 센다 — 하나를 공유하면 먼저 찍은 쪽이 다른 사유의 첫 발생을 통째로 덮는다. */
    private final Map<String, GateOpenLog> gateOpenLogs = new ConcurrentHashMap<>();

    private record GateOpenLog(AtomicLong lastNanos, AtomicLong passedThrough) {
    }

    private final ChatKickRepository kickRepository;
    private final ChatRoomEndedRepository roomEndedRepository;
    private final ChatBanRepository banRepository;

    /**
     * 전송 경로에서 방 종료를 다시 확인할지 판단해 알려주는 창. 세션별 상태라 레지스트리가 갖는다
     * (퇴장할 때 같이 회수되어야 하므로 여기서 들고 있으면 샌다).
     */
    private final ChatSessionRegistry registry;

    /**
     * 이 세션이 지금 살아있는 방에 쓰고 있는지 — 전송 경로용.
     *
     * <p>매 프레임 묻지 않는다. 창 안이면 Redis에 가지 않고 곧장 통과시킨다. 놓친 신호로 열리는 구간을
     * 창 길이만큼으로 줄이는 것이 목적이지, 실시간으로 아는 것이 목적이 아니기 때문이다.
     *
     * <p>다만 <b>종료를 확인한 뒤로는 다시 묻지 않고 계속 막는다</b>. 끝난 방이 다시 라이브가 되지는
     * 않으므로, 여기서 창을 다시 여는 건 그 방에 글이 더 쌓일 틈만 만든다.
     *
     * <p>조회 실패는 입장 게이트와 <b>같은 fail-open</b>이다 — Redis가 죽었다고 채팅이 통째로 멈추는 쪽이
     * 끝난 방에 몇 줄 더 쌓이는 것보다 나쁘다.
     */
    public Mono<Boolean> isRoomAlive(String sessionId, ChatSession session) {
        // 한 번 확인된 종료는 되묻지 않는다. 창으로만 두면 확인한 직후부터 다음 확인까지의 프레임이
        // 그대로 통과해 이력에 쌓인다 — 창 간격마다 한 건만 막는 꼴이라 막으려던 것이 거의 그대로 남는다.
        if (registry.isRoomKnownEnded(sessionId)) {
            return Mono.just(false);
        }
        if (!registry.shouldRecheckRoomAlive(sessionId)) {
            return Mono.just(true);
        }
        return roomEndedRepository.isEnded(session.roomId())
                .doOnNext(ended -> {
                    if (ended) {
                        registry.markRoomEnded(sessionId);
                    }
                })
                .map(ended -> !ended)
                .onErrorResume(e -> {
                    logGateOpen("방 종료 재확인", session, e);
                    return Mono.just(true);
                });
    }

    public Mono<Void> verify(ChatSession session) {
        // 방 종료는 게스트에게도 적용된다 — 끝난 방은 볼 것도 없다. 강퇴/밴만 게스트 대상이 아니다.
        //
        // 순서는 싼 것부터가 아니라 <b>넓은 것부터</b>다. 끝난 방은 신원과 무관하게 아무도 못 들어오고,
        // 강퇴는 이 방에서, 밴은 이 사람에게 걸린다. 앞에서 걸리면 뒤는 묻지 않으므로 왕복이 줄어든다.
        //
        // 왕복이 셋이 됐다. 하나로 묶는 것(pipeline)은 정합성과 무관한 순수 성능이라 여기서 하지 않는다 —
        // 묶으면 세 조회의 실패를 따로 분류하던 것이 한 덩어리가 되어, 어느 게이트가 열렸는지가 흐려진다.
        //
        // 비용은 접속당(메시지당이 아니다) 왕복 셋이다.
        return verifyRoomAlive(session)
                .then(Mono.defer(() -> verifyNotKicked(session)))
                .then(Mono.defer(() -> verifyNotBanned(session)));
    }

    /**
     * 종료된 방에는 들어갈 수 없다.
     *
     * <p>룸 토큰은 발급 시점에 라이브였다는 사실만 담는다. 종료 직전에 토큰을 받은 사람은 세션이 닫힌 뒤에도
     * 만료 전까지 다시 붙을 수 있어서, 종료 이벤트로 남긴 마커를 여기서 읽는다.
     */
    private Mono<Void> verifyRoomAlive(ChatSession session) {
        return roomEndedRepository.isEnded(session.roomId())
                .onErrorResume(e -> {
                    // 통과시키되 흔적은 남긴다 — 이 구간은 입장 모더레이션이 열린 구간이라,
                    // 사후에 "언제부터 게이트가 열려 있었는가"를 짚을 수 있어야 한다.
                    logGateOpen("방 종료", session, e);
                    return Mono.just(false);
                })
                .flatMap(ended -> ended
                        ? Mono.<Void>error(new EntryDeniedException(EntryDeniedException.Reason.ROOM_ENDED))
                        : Mono.empty());
    }

    private Mono<Void> verifyNotKicked(ChatSession session) {
        if (session.role() == ChatRole.GUEST) {
            return Mono.empty();
        }
        return kickRepository.isKicked(session.roomId(), session.userId())
                .onErrorResume(e -> {
                    logGateOpen("강퇴", session, e);
                    return Mono.just(false);
                })
                .flatMap(kicked -> kicked
                        ? Mono.<Void>error(new EntryDeniedException(EntryDeniedException.Reason.KICKED))
                        : Mono.empty());
    }

    /**
     * 플랫폼 밴은 어느 방에도 들어갈 수 없다.
     *
     * <p>방을 묻지 않는 것이 강퇴와의 차이다 — 그래서 {@code roomId}가 인자에 없다.
     *
     * <p>여기도 <b>fail-open</b>이다. Redis가 죽으면 밴된 사람이 잠시 들어올 수 있는데, 그 대가로
     * 정상 사용자 전원이 채팅을 잃는 쪽을 택하지 않는다 — 종료 마커·강퇴 조회와 같은 저울질이다.
     * 열린 구간은 사유를 따로 세어 남긴다.
     */
    private Mono<Void> verifyNotBanned(ChatSession session) {
        if (session.role() == ChatRole.GUEST) {
            return Mono.empty();
        }
        return banRepository.isBanned(session.userId())
                .onErrorResume(e -> {
                    logGateOpen("밴", session, e);
                    return Mono.just(false);
                })
                .flatMap(banned -> banned
                        ? Mono.<Void>error(new EntryDeniedException(EntryDeniedException.Reason.BANNED))
                        : Mono.empty());
    }

    /** 게이트가 열린 사실을 간격을 두고 남긴다. 첫 발생은 반드시 남는다. */
    private void logGateOpen(String what, ChatSession session, Throwable cause) {
        if (cause instanceof KickStoreCorruptedException corrupted) {
            logStoreCorrupted(what, session, corrupted);
            return;
        }
        GateOpenLog entry = counterFor(what);
        long total = entry.passedThrough().incrementAndGet();
        if (dueToLog(entry)) {
            // 누적 건수를 함께 남긴다 — 게이트가 열린 동안 몇 명이 그냥 통과했는지가 이 자리의 핵심 수치다.
            log.warn("{} 조회 실패 — 입장 허용(게이트 열림) roomId={} userId={} cause={} 누적통과={}",
                    what, session.roomId(), session.userId(), cause.getClass().getSimpleName(), total);
        }
    }

    /**
     * 키 타입이 어긋난 건 일시 장애와 등급이 다르다 — 재시도해도, Redis가 되살아나도 낫지 않는다.
     * 그래서 사유를 따로 세고 WARN이 아니라 ERROR로 남긴다. 같은 통에 담으면 곧 복구될 장애의 로그에
     * 묻혀, 이 방의 게이트가 사람이 올 때까지 계속 열려 있다는 사실이 드러나지 않는다.
     */
    private void logStoreCorrupted(String what, ChatSession session, KickStoreCorruptedException cause) {
        GateOpenLog entry = counterFor(what + "/키 타입 충돌");
        long total = entry.passedThrough().incrementAndGet();
        if (dueToLog(entry)) {
            log.error("{} 조회 불가(키 타입 어긋남) — 입장 허용(게이트 열림). 재시도로 낫지 않으니 사람이 그 키를 "
                            + "치워야 한다. key={} roomId={} 누적통과={}",
                    what, cause.getKey(), session.roomId(), total);
        }
    }

    private GateOpenLog counterFor(String what) {
        return gateOpenLogs.computeIfAbsent(what,
                key -> new GateOpenLog(new AtomicLong(System.nanoTime() - GATE_OPEN_LOG_INTERVAL_NANOS),
                        new AtomicLong()));
    }

    private boolean dueToLog(GateOpenLog entry) {
        long now = System.nanoTime();
        long last = entry.lastNanos().get();
        return now - last >= GATE_OPEN_LOG_INTERVAL_NANOS && entry.lastNanos().compareAndSet(last, now);
    }
}
