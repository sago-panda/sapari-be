package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

/**
 * (유저, 방) 동시 세션 자리 — 상한 집행, 반납, 꽉 찼을 때 죽은 자리 거두기, 그 누적 수.
 *
 * <p>상한 값의 검증(1 미만이면 기동 실패)과 등록 순서(자리 → 명부 → 방 색인, 실패 시 되돌리기)는
 * {@link ChatSessionRegistry} 에 남는다. 이 클래스는 자리 맵의 원자 구간만 책임진다. 세션이 응답 없는지는
 * 세션별 상태라 레지스트리가 판정을 넘긴다({@code ChatSessionRegistry::isUnresponsive}).
 */
final class SessionSlots {

    /** 이 파드가 실제로 집행하는 값. 상수가 아니라 필드인 이유는 운영이 바꿀 수 있어야 해서다. */
    private final int maxSessionsPerUserPerRoom;

    /** 상한을 세는 키. (유저, 방) 쌍이다 — 상한의 단위가 그것이라는 사실을 타입으로 남긴다. */
    private record UserInRoom(UUID userId, UUID roomId) {
    }

    /**
     * (유저, 방) → 그 조합의 sessionId 집합. 방 색인({@link LocalSessionIndex}) 와 같은 종류의 보조 인덱스다.
     *
     * <p>세지 않고 {@code local}(명부 — {@link LocalSessionIndex}) 을 훑으면 접속 1건이 Pod 전체 세션 수에 비례한다 — 재접속 폭풍이
     * 정확히 그 둘(접속 수 × 세션 수)이 동시에 큰 구간이라, 상한을 넣으려고 만든 코드가 그 구간의
     * 비용이 된다. 방이 비면 항목을 걷어내는 것도 같은 이유다(죽은 키 누적 방지).
     */
    private final Map<UserInRoom, Set<String>> userRoomSessions = new ConcurrentHashMap<>();

    /** 상한으로 거부한 누적 수. 로그를 솎아도 규모는 남긴다 — 정상 시청자를 막고 있는지는 이 수로 본다. */
    private final AtomicLong admissionCapped = new AtomicLong();

    /** 죽은 소켓이 들고 있던 자리를 거둔 누적 수. 거부와 짝으로 봐야 공격과 핸드오버가 갈린다. */
    private final AtomicLong slotsReaped = new AtomicLong();

    /** 상한 거부 로그 솎아내기. */
    private final LogThrottle admissionCapLog = new LogThrottle();

    /** 세션 생존 판정 — {@code ChatSessionRegistry::isUnresponsive}. */
    private final BiPredicate<String, Duration> isUnresponsive;

    SessionSlots(int maxSessionsPerUserPerRoom, BiPredicate<String, Duration> isUnresponsive) {
        this.maxSessionsPerUserPerRoom = maxSessionsPerUserPerRoom;
        this.isUnresponsive = isUnresponsive;
    }

    int max() {
        return maxSessionsPerUserPerRoom;
    }

    /** 자리 하나를 잡는다 — 상한 미만이면 넣고 true. 검사와 추가가 같은 원자 구간이다. */
    boolean tryAcquire(UUID userId, UUID roomId, String sessionId) {
        boolean[] admitted = {false};
        userRoomSessions.compute(new UserInRoom(userId, roomId), (key, sessionIds) -> {
            Set<String> ids = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            if (ids.size() < maxSessionsPerUserPerRoom) {
                ids.add(sessionId);
                admitted[0] = true;
            }
            return ids;
        });
        return admitted[0];
    }

    /** 상한 거부 1건을 세고 누적을 돌려준다. */
    long recordCapped() {
        return admissionCapped.incrementAndGet();
    }

    /** 상한 거부 로그 솎아내기 — 간격당 1건. */
    boolean shouldLogCapped() {
        return admissionCapLog.tryAcquire();
    }

    /**
     * (유저, 방) 자리 하나를 되돌린다. 빈 항목은 걷는다 — 남기면 그것이 누수원이다.
     *
     * <p>호출부가 둘이다 — 퇴장({@code unregister})과 등록 실패 보상. 둘이 각자 걷으면 한쪽만
     * "빈 항목 걷기" 를 빠뜨리는 변경이 조용히 들어온다. 다만 <b>키를 만드는 방식은 다르다</b>:
     * 퇴장은 세션 레코드에서 읽고, 보상은 인자에서 만든다({@code ChatSessionRegistry.rollbackLocalIndexes} 참고).
     */
    void release(UUID userId, UUID roomId, String sessionId) {
        userRoomSessions.computeIfPresent(new UserInRoom(userId, roomId), (userInRoom, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
    }

    /**
     * 자리가 꽉 찼을 때만 그 (유저, 방)의 <b>응답 없는</b> 세션을 거둔다. 거둔 수를 돌려준다.
     *
     * <p><b>왜 필요한가 — 그리고 무엇을 닫지 못하는가.</b> 소켓이 조용히 끊겨도 그 세션은 생존
     * 확인이 돌 때까지 자리를 들고 있다. 닫는 것은 그중 <b>마지막 구간 하나</b>다: 생존 루프가
     * 종료를 발화한 뒤 {@code cleanup} 이 자리를 걷기까지의 ≤8초.
     *
     * <p>⚠️ <b>첫 점검까지의 약 2분은 닫지 못한다 — 원리적으로 닫을 수 없다.</b> 한때 이 문단이
     * 그 2분을 reap 의 근거로 적었는데, 기한에 {@code pingInterval} 을 더해 넘기도록 고친 뒤로는
     * 거짓이다(그 수정이 없으면 살아 있는 세션을 끊는다 — 아래 참고). 더 근본적으로, 생존 시계가
     * <b>등록 시각으로 초기화</b>되므로 등록 직후 죽은 소켓은 첫 점검 전까지 "살아 있지만 조용한"
     * 시청자와 <b>구분 자체가 불가능</b>하다. 모바일 핸드오버로 조용히 끊기고 재접속하는 그
     * 시나리오가 정확히 그 구별 불가능한 경우다. 그 창을 닫으려면 "살아 있는 자리를 끊지 않는다" 를
     * 포기해야 하고, 그래서 포기하지 않는 쪽을 택했다.
     *
     * <p>상한의 "왜 5인가" 근거가 탭·새로고침 경합만 셌던 것은 사실이고, 그 창이 남아 있다는 것도
     * 사실이다. 즉 이 메서드는 그 문제를 <b>줄이지만 없애지 않는다</b> — 다음 사람이 그 창을 근거로
     * 기한을 되돌리지 않도록 적어 둔다.
     *
     * <p><b>왜 이 방식인가.</b> 대안은 "가장 오래된 세션을 밀어내기"였는데 그건 공격자에게
     * <b>남의 살아 있는 세션을 끊는 능력</b>을 준다(토큰 한 장으로 상한만큼 붙으면 정당한 사용자가
     * 쫓겨난다). 여기서 밀어내는 것은 이미 생존 기한을 넘긴 세션뿐이므로 그 능력이 생기지 않는다 —
     * 자리를 비우는 유일한 근거가 "그 소켓은 이미 죽었다" 다.
     *
     * <p>꽉 차지 않았으면 맵 조회 하나로 끝난다 — 접속마다 도는 자리라 그 경로에 비용을 두지 않는다.
     * 그 바깥 검사는 <b>권고일 뿐</b>이다: {@code computeIfPresent} 안에 full 재확인이 없으므로,
     * 사이에 다른 스레드의 퇴장이 자리를 비우면 꽉 차지 않은 집합에서도 죽은 자리를 거둔다. 거두는
     * 대상이 어차피 생존 확인이 가져갈 세션이고 자리 걷기가 멱등이라 피해가 없어 그대로 둔다 —
     * 안쪽 재확인을 넣으면 "꽉 찼을 때만" 이 두 곳에 생겨 둘이 어긋날 수 있다.
     *
     * <p>⚠️ <b>자리만 비운다 — 종료는 하지 않는다.</b> 한때 여기서 {@code terminateUnresponsive} 를
     * 불렀는데, 그러면 <b>Lettuce NIO 이벤트루프에서 최대 250ms spin</b> 하는 경로가 된다. 이 메서드는
     * {@code runSession} 안에서 돌고 그 upstream 마지막 신호가 입장 게이트의 Redis 응답이며
     * (streaming-app main 에 스케줄러 전환이 <b>0건</b>이다) 종료 하나가
     * {@link SessionOutbound#DEFAULT_COMPLETE_SPIN_NANOS}(50ms)짜리 예산을 <b>세션마다 새로</b> 받으므로 5건이면
     * 250ms 다. 송신부({@link SessionOutbound})가 두 자리에서 금지한 모양이다 — 1013 종료가 공유 예산을 쓰는 이유도,
     * "기다리는 쪽은 Netty 이벤트루프에서 spin할 수 있다 — 그 루프에 붙은 다른 커넥션까지 함께
     * 멈춘다" 도 같은 사실이다. 하필 이 메서드의 발동 조건(자리가 꽉 차고 전부 죽음)이 재접속 폭주
     * 구간이라, 그 구간에 이 파드의 모든 Redis 응답이 함께 멈춘다.
     *
     * <p>종료는 각 세션의 생존 확인이 이미 맡고 있다 — 그쪽은 {@code Schedulers.parallel()} 에서
     * 세션 하나씩 돈다. 자리를 선반납해도 그 수거가 늦어지지 않는다(다음 틱이 간격 하나 안에 온다).
     * 자리 회계와 종료를 가른 것이 이 설계의 값이다.
     *
     * <p>그래서 거둔 세션이 {@code local} 에는 잠깐 남는다 — {@code (유저, 방)}당 실제 세션 수가
     * <b>최대 2배(10), 지속 ≤8초</b>가 된다. 그 수는 종료 상한 둘에서 나온다:
     * {@code FORCED_CLOSE_GRACE 3s + CLOSE_FLUSH_TIMEOUT 5s}. 그리고 reap 은 키당
     * <b>{@code livenessDeadline + pingInterval}</b>(기본 120초)에 한 번보다 자주 발동할 수 없다 —
     * 새로 들어온 다섯이 그 기한 전에는 unresponsive 가 되지 않는다. <b>8s ≪ 120s</b> 라 정상 상태
     * 상한이 10 에서 멈추고, 그 위로 쌓이지 않는다.
     * ⚠️ 이 결론은 그 비에 매달려 있고 <b>묶는 양은 기한 하나가 아니라 기한 + 간격이다</b>(한때
     * "90초" 와 "liveness-deadline 을 8초 밑으로" 로 적어 손잡이를 잘못 가리켰다). 그 합을 8초
     * 밑으로 내리거나 종료 상한을 키우면 선형으로 증가한다(실측: 정리 없이 사이클 4회 →
     * local 5·10·15·20).
     * ⚠️ 그리고 이 ≤8초에는 <b>틱 지연 항이 없다</b> — 뺀 250ms spin 이 사라진 것이 아니라
     * {@code Schedulers.parallel} 로 옮겨 갔고, 그 풀이 밀리면 그만큼 이 창에 더해진다. 그 교환을
     * 택한 이유는 재접속 폭주에 이 파드의 Redis 응답 전체를 세우는 쪽이 더 나쁘기 때문이다.
     * ⚠️ 그리고 그 하한은 <b>강제되지 않는다</b> — {@code SessionTimeouts} 의 가드 셋(양수 ·
     * 기한 ≥ 2×간격 · 최소 수명 > 첫 점검)을 {@code (기한 2s, 간격 1s)} 가 전부 통과한다(실측).
     * 가드를 더하지 않는 이유는 {@code ChatSessionLivenessTest} 가 기한 {@code 3s} 를 <b>의도적으로</b>
     * 써서 기다리지 않기 때문이다 — 그 구성에서는 세션이 한 개라 위 증가가 문제가 되지 않는다.
     * 즉 위 수는 <b>운영 기본값에서의</b> 것이다.
     *
     * <p>그 창이 파생시키는 것 둘, 알고 둔다. 첫째, 거둔 세션이 아직 {@code local} 에 있으므로
     * {@code ViewerCounts.reregister} 의 {@code containsKey} 가드를 통과해 하트비트를 한두 주기 더 받는다.
     * 둘째, 그래서 같은 창의 Redis 명부가 최대 2배의 항목을 든다 — 다만 <b>보고되는 시청자 수는
     * 바뀌지 않는다</b>(그 수가 {@code HVALS distinct} 라 같은 유저의 중복은 세지 않고, 이 창의
     * 중복은 정의상 같은 (유저, 방)이다). 한때 이 줄이 "시청자 수가 그만큼 부푼다" 로 적혀 바로
     * 뒤 문장과 모순이었다. 둘 다 유계이고
     * (≤8초) 자가치유라 손대지 않는다 — {@code ViewerCounts.reregister} 가 하트비트 과다계수를 두고 세운 판단과 같다
     * ("완전히 닫으려면 세션별 직렬화가 필요하고, 유계·자가치유인 과다계수에 그 값은 과하다").
     * 시청자 수는 {@code HVALS distinct} 라 <b>같은 유저의</b> 중복은 수를 바꾸지 않는다는 점도 있다.
     */
    int reapUnresponsiveIfFull(UUID userId, UUID roomId, Duration livenessDeadline) {
        UserInRoom key = new UserInRoom(userId, roomId);
        Set<String> occupied = userRoomSessions.get(key);
        if (occupied == null || occupied.size() < maxSessionsPerUserPerRoom) {
            return 0;
        }
        List<String> dead = new ArrayList<>();
        userRoomSessions.computeIfPresent(key, (userInRoom, sessionIds) -> {
            for (String sessionId : sessionIds) {
                if (isUnresponsive.test(sessionId, livenessDeadline)) {
                    dead.add(sessionId);
                }
            }
            sessionIds.removeAll(dead);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
        if (!dead.isEmpty()) {
            // 센다 — 거부와 수거를 가르지 못하면 "공격" 과 "모바일 핸드오버" 를 가를 수 없고,
            // 그 둘을 가르는 것이 이 상한을 운영하는 사람의 일이다. sessionCut(LIVENESS) 로는
            // 안 된다(태그가 사유 하나라 생존 루프가 거둔 것과 구분되지 않는다).
            slotsReaped.addAndGet(dead.size());
        }
        return dead.size();
    }

    long slotsReaped() {
        return slotsReaped.get();
    }

    long admissionCapped() {
        return admissionCapped.get();
    }
}
