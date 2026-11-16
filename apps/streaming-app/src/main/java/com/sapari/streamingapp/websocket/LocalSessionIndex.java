package com.sapari.streamingapp.websocket;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 이 Pod 의 세션 색인 — {@code local}(sessionId → 세션 묶음) 명부와 그 보조 인덱스인 방 색인.
 *
 * <p>{@link ChatSessionRegistry} 가 등록·해제 순서와 그 보상을 정하고, 이 클래스는 두 맵의 원자 구간만
 * 책임진다. 방 색인의 수명을 따르는 캐시(시청자 수 캐시 · 마지막으로 보낸 수)는 이 클래스가 갖지 않지만
 * 그 쓰기·지우기는 <b>방 항목의 원자 구간 안에서</b> 돌아야 한다 — 그래서 {@link #removeSession} ·
 * {@link #ifRoomPresent} 가 그 구간 안에서 호출자 코드를 실행한다. 구간 밖으로 꺼내면 방이 빈 직후에 늦게
 * 도착한 쓰기가 캐시를 되살려 그 캐시가 누수원이 된다.
 */
final class LocalSessionIndex {

    /** sessionId → (도메인 세션 + 아웃바운드 Sink). 로컬 메모리(이 Pod 한정). */
    private final Map<String, LocalSession> local = new ConcurrentHashMap<>();

    /**
     * roomId → 그 방의 sessionId 집합. {@link #local}의 보조 인덱스다.
     *
     * <p>방 fan-out이 Pod 전체 세션을 훑지 않게 한다 — 메시지 1건마다 전체 스캔이면 다른 방 세션 수에
     * 비례해 비용이 붙고, 그걸 이벤트루프에서 한다. 갱신은 {@code compute} 계열로만 해서 등록/해제가
     * 서로 끼어들지 않게 하고, 방이 비면 항목 자체를 지운다(죽은 방 누적 방지).
     */
    private final Map<UUID, Set<String>> roomSessions = new ConcurrentHashMap<>();

    LocalSession get(String sessionId) {
        return local.get(sessionId);
    }

    boolean contains(String sessionId) {
        return local.containsKey(sessionId);
    }

    /** 명부의 라이브 뷰 — 순회 중 등록·해제가 끼어도 된다(ConcurrentHashMap 뷰). */
    Collection<LocalSession> sessions() {
        return local.values();
    }

    void put(String sessionId, LocalSession session) {
        local.put(sessionId, session);
    }

    /**
     * 방 색인에 넣는다.
     *
     * <p>compute — 집합 생성과 추가를 한 원자 구간에 묶는다. computeIfAbsent 후 add로 나누면 그 사이
     * 마지막 퇴장이 빈 집합을 걷어내 방금 넣은 세션이 인덱스에서 사라질 수 있다.
     */
    void addToRoom(UUID roomId, String sessionId) {
        roomSessions.compute(roomId, (room, sessionIds) -> {
            Set<String> ids = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            ids.add(sessionId);
            return ids;
        });
    }

    /**
     * 명부와 방 색인에서 뺀다. 방이 비면 항목까지 걷어내고, 그 <b>같은 원자 구간 안에서</b>
     * {@code onRoomEmptied} 를 돌린다.
     */
    void removeSession(String sessionId, UUID roomId, Runnable onRoomEmptied) {
        local.remove(sessionId);
        // 방이 비면 항목까지 걷어낸다 — 남기면 방송이 끝난 방이 계속 쌓인다(인덱스 자체가 누수원이 됨).
        roomSessions.computeIfPresent(roomId, (room, sessionIds) -> {
            sessionIds.remove(sessionId);
            if (sessionIds.isEmpty()) {
                onRoomEmptied.run();
                return null;
            }
            return sessionIds;
        });
    }

    /** 방 항목이 있을 때만, 그 항목의 원자 구간 안에서 {@code action} 을 돌린다. */
    void ifRoomPresent(UUID roomId, Runnable action) {
        roomSessions.computeIfPresent(roomId, (room, sessionIds) -> {
            action.run();
            return sessionIds;
        });
    }

    boolean hasRoom(UUID roomId) {
        return roomSessions.containsKey(roomId);
    }

    /** 지금 로컬 세션이 있는 방들의 사본 — 순회 중 방이 생기거나 사라져도 이 목록은 고정이다. */
    List<UUID> roomsSnapshot() {
        return List.copyOf(roomSessions.keySet());
    }

    /**
     * 방 인덱스를 타고 해당 방 세션만 순회한다. Pod 전체 스캔을 피하는 자리라 방 fan-out은 모두 여기를 통한다.
     *
     * <p>인덱스에는 있지만 {@link #local}에서 이미 빠진 세션은 건너뛴다 — 퇴장 중인 세션과 겹칠 수 있고,
     * 그 세션은 어차피 곧 닫힌다.
     */
    void forEachInRoom(UUID roomId, Consumer<LocalSession> action) {
        Set<String> sessionIds = roomSessions.get(roomId);
        if (sessionIds == null) {
            return;
        }
        for (String sessionId : sessionIds) {
            LocalSession ls = local.get(sessionId);
            if (ls != null) {
                action.accept(ls);
            }
        }
    }

    /**
     * 이 Pod에서 그 사람의 세션을 전부 훑는다.
     *
     * <p><b>방 색인을 못 쓴다</b> — 계정 조치는 방을 모른 채로 오고, {@code userId → 세션} 색인은 두지
     * 않았다. 두면 {@code register}/{@code unregister}마다 동기화 대상이 하나 늘고 그 어긋남이 조용한
     * 버그가 되는데, 그 비용을 낼 만큼 이 경로가 잦지 않다.
     *
     * <p>⚠️ <b>{@code ChatSessionRegistry.userRoomSessions} 가 생겼지만 이 경로는 여전히 훑는다.</b> 그 색인의 키는
     * {@code (userId, roomId)} 쌍이라 "이 사람의 세션 전부" 를 물으면 방을 모르는 채로 전체 항목을
     * 뒤져야 한다 — 이 스캔보다 싸지 않다. 위 문단의 경고가 틀렸다는 뜻도 아니다: 그 색인에
     * 정확히 그 조용한 버그가 생길 수 있고(퇴장이 항목을 걷지 않으면 그 유저가 그 방에 영구히
     * 못 들어온다), 그래서 {@code SessionAdmissionCapTest} 가 그 한 가지를 따로 고정한다. 색인을
     * 하나 더 두는 값은 <b>메시지마다 도는 경로</b>에서만 나오고, 이 경로는 시간당 몇 건이다.
     * 12,000 세션 전체 스캔이 58µs다(실측, 워밍업 후 최소값).
     *
     * <p><b>비용을 폭발시키는 변수는 이벤트 빈도가 아니라 파드당 세션 수다.</b> 스캔은 선형이 아니다 —
     * 12,000에서 58µs인데 100,000에서 4.2ms로, 8.3배 입력에 70배 넘게 든다(캐시 미스). 시간당 한 건이어도
     * 10만 세션이면 한 건이 스레드를 그만큼 잡는다.
     *
     * <p>그리고 <b>이벤트 하나가 스캔을 두 번</b> 한다(사유 전송 + 종료). 위 수치는 1회분이므로 실제
     * 비용은 그 두 배다 — 10만이면 8ms 남짓이다. <b>파드당 2~3만 세션</b>이 다시 판단할 자리다.
     *
     * <p>돌 때 밟는 것은 이벤트루프가 아니라 Redis pub/sub I/O 스레드다({@code listenToChannel}이 자기
     * 연결을 만든다). 시청자 커넥션이 멈추지는 않지만 한가한 스레드도 아니다 — 방 팬아웃과 커맨드 응답이
     * 같은 곳에 얹힌다.
     */
    void forEachSessionOf(UUID userId, Consumer<LocalSession> action) {
        for (LocalSession ls : local.values()) {
            if (ls.session().userId().equals(userId)) {
                action.accept(ls);
            }
        }
    }

    int sessionCount() {
        return local.size();
    }

    int roomCount() {
        return roomSessions.size();
    }
}
