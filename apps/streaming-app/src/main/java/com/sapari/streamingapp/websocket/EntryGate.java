package com.sapari.streamingapp.websocket;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatCheckpoint;
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatPolicy;
import com.sapari.chat.application.port.PolicyReadFailure;
import com.sapari.chat.domain.exception.ChatSendUnavailableException;
import com.sapari.chat.domain.exception.KickStoreCorruptedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatWithdrawalRepository;
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
 * <p>검사는 넷이다 — <b>방 종료 마커</b>(게스트 포함 전원), <b>강퇴</b>(회원만), <b>플랫폼 밴</b>(회원만),
 * <b>탈퇴 신청</b>(회원만). 강퇴는 이 방에서 나가는 것이고 밴은 어느 방에도 못 들어오는 것이라, 축이 달라
 * 따로 본다. 탈퇴는 밴과 축이 같지만(사람) 근거가 다르다 — 이미 받아 둔 토큰이 탈퇴 뒤에도 유효하다.
 *
 * <p><b>입장 검사는 전부 fail-open</b>: 조회 실패(Redis 장애) 시 입장을 <i>허용</i>한다. 막으면 1008 로
 * 닫히고 프론트는 1008 에 재접속하지 않으므로, Redis 가 살아난 뒤에도 정상 시청자가 채팅에서 빠진 채
 * 남는다. 통과한 사람이 할 수 있는 것은 읽기뿐이다 — 전송은 판정을 못 읽으면 거절하고({@link #isRoomAlive},
 * {@code SendChatService}), 읽기는 게스트도 한다. 열린 구간은 사유별로 스로틀 로그 + 누적 통과 건수를 남긴다.
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

    /**
     * 솎기 래치 + 누적 수.
     *
     * <p>⚠️ 수 필드 이름이 중립({@code occurrences})인 이유: 이 기계를 <b>거부</b> 쪽도 쓴다
     * (아래 {@code logDenial}). 한때 이름이 "통과" 였고, 그때는 거부 건수를 통과라는 이름의
     * 필드에 담는 모양이었다 — 읽는 사람이 뜻을 거꾸로 받는다. 무엇의 수인지는 맵 키
     * ({@code what})가 말한다.
     */
    private record GateOpenLog(AtomicLong lastNanos, AtomicLong occurrences) {
    }

    private final ChatKickRepository kickRepository;
    private final ChatRoomEndedRepository roomEndedRepository;
    private final ChatBanRepository banRepository;
    private final ChatWithdrawalRepository withdrawalRepository;

    /**
     * 관측 포트. 아래 스로틀 카운터는 프로세스 안에만 있었고 솎인 로그 줄은 아예 남지 않는다 —
     * <b>게이트가 열려 있던 구간을 밖에서 알 수 없었다.</b> 입장 게이트가 열리면 제재받은 사람이
     * 방에 들어오고, 전송 재확인이 열리면 끝난 방에 글이 들어온다.
     *
     * <p>초기화하지 않는다 — {@code @RequiredArgsConstructor} 는 값이 붙은 final 필드를 생성자에서
     * 빼므로, 초기화하면 영원히 null 인 채로 컴파일된다.
     */
    private final ChatMetrics metrics;

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
     * <p>조회 실패는 입장 게이트와 <b>반대로 거절</b>이다({@link ChatSendUnavailableException}). 종료 확인과
     * 실시간 전달이 같은 Redis 라, 못 읽는 동안 통과시켜도 보는 사람 없이 이력에만 남는다. 다음 프레임이
     * 다시 묻도록 창을 되돌린다 — 그대로 두면 이번 실패가 창을 소비해 다음 창 길이만큼은 묻지 않고 통과한다.
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
                    registry.requireRoomAliveRecheck(sessionId);
                    return Mono.error(refuseRoomAliveUnreadable(session, e));
                });
    }

    public Mono<Void> verify(ChatSession session) {
        // 방 종료는 게스트에게도 적용된다 — 끝난 방은 볼 것도 없다. 강퇴/밴만 게스트 대상이 아니다.
        //
        // 순서는 싼 것부터가 아니라 <b>넓은 것부터</b>다. 끝난 방은 신원과 무관하게 아무도 못 들어오고,
        // 강퇴는 이 방에서, 밴은 이 사람에게 걸린다. 앞에서 걸리면 뒤는 묻지 않으므로 왕복이 줄어든다.
        //
        // 왕복이 넷이 됐다. 하나로 묶는 것(pipeline)은 정합성과 무관한 순수 성능이라 여기서 하지 않는다 —
        // 묶으면 네 조회의 실패를 따로 분류하던 것이 한 덩어리가 되어, 어느 게이트가 열렸는지가 흐려진다.
        //
        // 비용은 접속당(메시지당이 아니다) 왕복 넷이다.
        //
        // ⚠️ 병렬화(Mono.zip)도 재 보고 하지 않았다. 분류와 우선순위는 지킬 수 있지만 셈이 이렇다:
        //
        //            명령 수   지연        메모
        //   현재 허용   4       4 RTT      대다수 경로
        //   현재 거부   1       1 RTT      앞에서 걸리면 뒤를 묻지 않는다(아래 테스트가 계약으로 고정)
        //   병렬 허용   4       1 RTT      명령 수는 그대로
        //   병렬 거부   4       1 RTT      명령 수가 는다
        //
        // Redis 는 명령 수 바운드라 허용 경로의 부하는 그대로이고 거부 경로만 늘어난다. 얻는 것은
        // 핸드셰이크당 1~2ms 지연뿐인데, 그 대가로 "앞에서 걸리면 왕복이 줄어든다" 는 계약과 그걸
        // 고정한 두 테스트(never() 단언)를 바꿔야 한다. 같은 티켓에서 팬아웃 직렬화를 3,000배 줄인
        // 것과 비교하면 값이 서지 않는다. 재접속 폭풍에서 지연이 실제로 꼬리에 닿으면 다시 볼 것.
        return verifyRoomAlive(session)
                .then(Mono.defer(() -> verifyNotKicked(session)))
                .then(Mono.defer(() -> verifyNotBanned(session)))
                .then(Mono.defer(() -> verifyNotWithdrawing(session)));
    }

    /**
     * 탈퇴를 신청한 사람이 <b>이미 받아 둔</b> 룸 토큰으로 다시 들어오는 것을 막는다.
     *
     * <p>토큰 검증은 서명과 만료만 본다. 탈퇴 이벤트는 그 순간 열려 있던 세션을 끊을 뿐이라, 이 분기가
     * 없으면 끊긴 직후 같은 토큰으로 다시 들어와 그 연결이 세션 수명(토큰의 만료와 무관하다)까지 산다.
     * 밴 뒤에 둔다 — 대상이 같고(사람), 밴으로 이미 거부된 입장은 이 조회를 치르지 않는다.
     */
    private Mono<Void> verifyNotWithdrawing(ChatSession session) {
        if (session.role() == ChatRole.GUEST) {
            return Mono.empty();
        }
        return withdrawalRepository.isWithdrawing(session.userId())
                .onErrorResume(e -> {
                    logGateOpen("탈퇴", ChatPolicy.WITHDRAWING, ChatCheckpoint.ENTRY, session, e);
                    return Mono.just(false);
                })
                .flatMap(withdrawing -> withdrawing
                        ? Mono.<Void>error(deniedAndLogged(EntryDeniedException.Reason.WITHDRAWING, "탈퇴", session))
                        : Mono.empty());
    }

    /**
     * 종료된 방에는 들어갈 수 없다.
     *
     * <p>룸 토큰은 발급 시점에 라이브였다는 사실만 담는다. 종료 직전에 토큰을 받은 사람은 세션이 닫힌 뒤에도
     * 만료 전까지 다시 붙을 수 있어서, 종료 이벤트로 남긴 마커와 live 의 종료 키를 여기서 읽는다.
     */
    private Mono<Void> verifyRoomAlive(ChatSession session) {
        return roomEndedRepository.isEnded(session.roomId())
                .onErrorResume(e -> {
                    // 통과시키되 흔적은 남긴다 — 이 구간은 입장 모더레이션이 열린 구간이라,
                    // 사후에 "언제부터 게이트가 열려 있었는가"를 짚을 수 있어야 한다.
                    logGateOpen("방 종료", ChatPolicy.ROOM_ENDED, ChatCheckpoint.ENTRY, session, e);
                    return Mono.just(false);
                })
                .flatMap(ended -> ended
                        ? Mono.<Void>error(deniedAndLogged(EntryDeniedException.Reason.ROOM_ENDED, "방 종료", session))
                        : Mono.empty());
    }

    private Mono<Void> verifyNotKicked(ChatSession session) {
        if (session.role() == ChatRole.GUEST) {
            return Mono.empty();
        }
        return kickRepository.isKicked(session.roomId(), session.userId())
                .onErrorResume(e -> {
                    logGateOpen("강퇴", ChatPolicy.KICK, ChatCheckpoint.ENTRY, session, e);
                    return Mono.just(false);
                })
                .flatMap(kicked -> kicked
                        ? Mono.<Void>error(deniedAndLogged(EntryDeniedException.Reason.KICKED, "강퇴", session))
                        : Mono.empty());
    }

    /**
     * 플랫폼 밴은 <b>신원이 실린 입장</b>이면 어느 방에도 들어갈 수 없다.
     *
     * <p>방을 묻지 않는 것이 강퇴와의 차이다 — 그래서 {@code roomId}가 인자에 없다.
     *
     * <p>⚠️ <b>"어느 방에도" 를 신원 조건 없이 적으면 거짓이다.</b> 아래가 {@code GUEST} 를
     * 건너뛰고, live 의 게스트 입장은 <b>입장마다 새 임의 userId</b> 를 발급한다
     * ({@code EnterLiveService.issueGuestToken}). 그래서 밴된 사람이 Authorization 헤더를 떼고
     * 입장하면 이 조회의 키가 아예 없다. 피해는 작다 — 게스트는 수신 전용이고 발언·특권 뷰가
     * 없다. 막으려면 게스트 신원을 안정화해야 하는데 그건 추적 가능한 비회원 식별자를 만드는
     * 일이라 이 게이트의 범위가 아니다(별 티켓).
     *
     * <p>여기도 <b>fail-open</b>이다. Redis가 죽으면 밴된 사람이 잠시 들어올 수 있는데, 그 대가로
     * 정상 사용자 전원이 채팅을 잃는 쪽을 택하지 않는다 — 종료 마커·강퇴 조회와 같은 저울질이다.
     * 열린 구간은 사유를 따로 세어 남긴다.
     *
     * <p>그 저울질은 live-app 이 폐기 조회를 갖게 된 뒤에도 <b>그대로다</b>. 같은 Redis 가 흔들리면
     * 회원은 live 쪽에서 503 을 받아 <b>새 룸 토큰을 못 받으므로</b>, 이 구간에 도달하는 것은
     * 장애 직전에 발급된 토큰(≤ 룸 토큰 {@code exp})뿐이다. 즉 열리는 창이 좁아졌을 뿐 방향은
     * 같다. 한때 그 경로가 회원을 <b>GUEST 로 강등</b>시켜 이 조회를 건너뛰게 만들었는데, 그건
     * 결함으로 닫혔다({@code TokenRevocationFilter}).
     */
    private Mono<Void> verifyNotBanned(ChatSession session) {
        if (session.role() == ChatRole.GUEST) {
            return Mono.empty();
        }
        return banRepository.isBanned(session.userId())
                .onErrorResume(e -> {
                    logGateOpen("밴", ChatPolicy.BAN, ChatCheckpoint.ENTRY, session, e);
                    return Mono.just(false);
                })
                .flatMap(banned -> banned
                        ? Mono.<Void>error(deniedAndLogged(EntryDeniedException.Reason.BANNED, "밴", session))
                        : Mono.empty());
    }

    /**
     * 게이트가 열린 사실을 간격을 두고 남긴다. 첫 발생은 반드시 남는다.
     *
     * <p>로그 라벨({@code what})과 지표 태그를 <b>둘 다</b> 받는다. 라벨은 사람이 읽는 문장이고
     * 스로틀 카운터의 맵 키라 한글 그대로 쓰는데, 그걸 태그 값으로 쓰면 지표 이름에 한글이 들어가고
     * 문구를 다듬는 순간 시계열이 갈라진다. 열거로 받으면 호출부를 늘릴 때 컴파일러가 묻는다.
     */
    private void logGateOpen(String what, ChatPolicy policy, ChatCheckpoint checkpoint,
            ChatSession session, Throwable cause) {
        if (cause instanceof KickStoreCorruptedException corrupted) {
            logStoreCorrupted(what, policy, checkpoint, session, corrupted);
            return;
        }
        metrics.policyNotEnforced(policy, checkpoint, PolicyReadFailure.UNAVAILABLE);
        GateOpenLog entry = counterFor(what);
        long total = entry.occurrences().incrementAndGet();
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
    private void logStoreCorrupted(String what, ChatPolicy policy, ChatCheckpoint checkpoint,
            ChatSession session, KickStoreCorruptedException cause) {
        metrics.policyNotEnforced(policy, checkpoint, PolicyReadFailure.STORE_CORRUPTED);
        GateOpenLog entry = counterFor(what + "/키 타입 충돌");
        long total = entry.occurrences().incrementAndGet();
        if (dueToLog(entry)) {
            log.error("{} 조회 불가(키 타입 어긋남) — 입장 허용(게이트 열림). 재시도로 낫지 않으니 사람이 그 키를 "
                            + "치워야 한다. key={} roomId={} 누적통과={}",
                    what, cause.getKey(), session.roomId(), total);
        }
    }

    /**
     * 거부를 남기고 그 예외를 만든다. 삼항의 거부 가지 안에서만 평가되므로 허용 경로는 안 거친다.
     *
     * <p>로그와 예외를 한 자리에 묶는 이유는 거부 분기가 전부 같은 모양이라 한쪽만 빠뜨리기 쉬웠기
     * 때문이다 — 지금 빠뜨리면 그 사유의 분모만 조용히 비고, 그건 "집행이 0" 과 출력이 같다.
     */
    private EntryDeniedException deniedAndLogged(EntryDeniedException.Reason reason, String what,
            ChatSession session) {
        logDenial(what, session);
        return new EntryDeniedException(reason);
    }

    /**
     * 입장 거부를 간격을 두고 남긴다. 첫 발생은 반드시 남는다.
     *
     * <p>강등 분기는 전부 신호를 갖는데 거부 분기 셋은 로그도 지표도 없었다 — 그래서
     * {@code chat.policy.open{policy=...}} 의 <b>분모</b>가 비어 있었다. 당직자가 "집행되고 있고
     * 걸린 사람이 없다" 와 "조회가 전원에게 조용히 false 를 돌려준다" 를 가를 수 없는 상태다.
     *
     * <p>새 미터를 만들지 않고 {@link #counterFor}/{@link #dueToLog} 를 재사용한다 — 새 포트 표면이
     * 0 이다. 스로틀 키는 사유별로 갈린다({@code what} 이 맵 키다), 그래서 한 사유가 다른 사유의
     * 첫 발생을 덮지 않는다 — 이 클래스가 적어 둔 그 결론({@code "사유별로 따로 센다"})과 같다.
     *
     * <p>⚠️ 이 로그는 <b>게이트 거부 넷</b>만 덮는다. {@code TOO_MANY_SESSIONS} 는 여기가 아니라
     * 레지스트리가 세고({@code chat.sessions.admission_capped}), 그래서 거부 사유 전체의 합은
     * 여전히 한 자리에서 안 보인다. 그걸 닫으려면 {@code ChatMetrics} 메서드가 하나 늘어야 한다.
     */
    private void logDenial(String what, ChatSession session) {
        GateOpenLog entry = counterFor("거부/" + what);
        long total = entry.occurrences().incrementAndGet();
        if (dueToLog(entry)) {
            log.warn("입장 거부 — {} roomId={} userId={} 누적거부={}",
                    what, session.roomId(), session.userId(), total);
        }
    }

    /**
     * 방 종료 재확인을 못 읽어 전송을 거절한다. 입장 쪽 열림과 같은 스로틀 기계를 쓰되 키를 따로 둔다.
     *
     * <p>던지지 않는다 — 예외를 <b>만들어 돌려줄</b> 뿐이고, 호출부가 그것을 {@code Mono.error} 로 싣는다.
     */
    private ChatSendUnavailableException refuseRoomAliveUnreadable(ChatSession session, Throwable cause) {
        metrics.sendRefused(ChatPolicy.ROOM_ENDED);
        GateOpenLog entry = counterFor("전송 거절/방 종료 재확인");
        long total = entry.occurrences().incrementAndGet();
        if (dueToLog(entry)) {
            log.warn("방 종료 재확인 실패 — 전송 거절 roomId={} cause={} 누적거절={}",
                    session.roomId(), cause.getClass().getSimpleName(), total);
        }
        return new ChatSendUnavailableException(cause);
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
