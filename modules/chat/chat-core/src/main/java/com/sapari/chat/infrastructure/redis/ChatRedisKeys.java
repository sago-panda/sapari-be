package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

/**
 * chat이 소유한 Redis 키 패턴. package-private — 키 문자열이 어댑터 밖으로 새지 않게 한다.
 *
 * <p>이 패키지의 어댑터가 전부 여기를 거치므로, 읽는 쪽과 쓰는 쪽이 다른 키를 보는 drift가 생기지 않는다.
 * 강퇴 쓰기(SADD)처럼 blocking 스택 어댑터가 나중에 붙어도 <b>같은 패키지</b>에 두면 그대로 공유된다 —
 * 다른 모듈에서 쓰겠다고 public으로 열지 말 것. 키를 아는 코드가 늘어나는 만큼 drift 위험이 늘어난다.
 *
 * <p>밴 상태 키({@code chat:banned:{userId}})도 여기 있다 — 강퇴 에스컬레이션이 쓰는 chat 소유 키다.
 * 반면 라이브 진행 여부처럼 live가 소유·판정하는 키는 여기 두지 않는다.
 *
 * <p><b>새 키는 {@code chat:}로 시작한다.</b> 네 앱이 같은 Redis 논리 DB를 쓰므로 이름이 겹치면 그 키는
 * 타입이 어긋난 채 살아 있고, 그때부터 그 방의 해당 기능은 재시도로도 복구되지 않는다. 접두를 붙이면
 * "이 키는 chat 것"이 규약이 아니라 구조가 된다. 예외는 {@code ratelimit:chat:} 하나로, 접두 위치만
 * 다를 뿐 이미 소유가 이름에 드러나 있어 그대로 둔다.
 */
final class ChatRedisKeys {

    private ChatRedisKeys() {
    }

    /** sessionId → userId 매핑 HASH (멀티탭 지원, 라이브 종료 시 삭제) */
    static String sessions(UUID roomId) {
        return "chat:room:" + roomId + ":sessions";
    }

    /** 방 강퇴 userId 집합 SET (재접속 차단, 라이브 종료 시 만료 부여) */
    static String kicked(UUID roomId) {
        return "chat:kicked:" + roomId;
    }

    /** 방 종료 마커 (종료 후 남은 토큰으로 재입장하는 것을 막는다) */
    static String roomEnded(UUID roomId) {
        return "chat:room:" + roomId + ":ended";
    }

    /**
     * 플랫폼 밴 캐시. <b>키가 있으면 밴</b>이고 값은 보지 않는다 — 만료가 곧 해제라 상태를 따로 두지 않는다.
     * 방이 아니라 사람에게 붙는다: 강퇴는 방 단위지만 밴은 플랫폼 전체다.
     */
    static String banned(UUID userId) {
        return "chat:banned:" + userId;
    }

    /**
     * 그 사용자의 밴 미러에 닿은 가장 큰 변경 번호. 늦게 도착한 적용·해제를 거절하는 데 쓴다.
     * {@code chat:banned:} 아래에 두지 않는 것은 그 접두가 "키가 있으면 밴" 이라는 뜻을 갖기 때문이다.
     */
    static String banVersion(UUID userId) {
        return "chat:ban:ver:" + userId;
    }

    /** 탈퇴 신청 기록. <b>키가 있으면 입장 거부</b>이고 값은 보지 않는다. 수명은 어댑터가 설정에서 받는다. */
    static String withdrawing(UUID userId) {
        return "chat:withdrawing:" + userId;
    }

    /**
     * 전파된 욕설 사전 스냅샷 — <b>버전과 두 집합이 이 키 하나에 함께</b> 들어 있다.
     *
     * <p>방이 아니라 플랫폼에 붙는다(사전은 글로벌 1개다). 집합 둘을 키 둘로 가르지 않은 것이
     * 결정이다 — 읽는 쪽이 두 번 읽으면 그 사이에 한쪽만 바뀐 사전을 볼 수 있고, 화이트리스트
     * 항목은 자기가 덮는 욕설과 함께여야 의미가 있으므로 반쪽 조합은 실제로 다르게 마스킹한다.
     * 키가 하나면 읽기가 원자인 것이 <b>구조로</b> 성립한다(GET 한 번).
     *
     * <p>TTL 이 없다. 이 키가 사라지면 받는 쪽은 "아직 발행된 적 없음" 으로 읽고 번들 사전으로
     * 뜨는데, 그건 정본과 다른 상태다 — 만료로 그 상태에 빠뜨릴 이유가 없다.
     */
    static String dictSnapshot() {
        return "chat:dict:snapshot";
    }

    /**
     * 사전 버전 카운터. 발행이 <b>조건부로</b> +1 하고 그 값을 스냅샷 본문 머리에 박는다 —
     * 발행자가 집어 둔 번호가 아직 그대로일 때만이다({@code ProfanityDictionarySnapshotPublisher}).
     *
     * <p>스냅샷 안에도 같은 값이 있는데 키를 따로 두는 이유는 <b>주기 자가치유의 비용</b>이다 —
     * 알림을 놓친 Pod 이 주기로 확인할 때 수십 KB 스냅샷 대신 몇 바이트를 읽는다. 두 값이
     * 어긋나 보일 수는 없다: 발행이 증가와 본문 쓰기를 한 스크립트에서 하므로 밖에서 둘을
     * 따로 볼 수 없다. ⚠️ 조건이 깨진 시도는 <b>아무것도 쓰지 않는다</b> — 그래서 실패한
     * 재시도가 번호를 태워 이 불변식을 깨뜨리지 않는다({@code INCR} 뒤에 조건을 걸었다면
     * 깨진다).
     */
    static String dictVersion() {
        return "chat:dict:version";
    }

    /**
     * 사전 변경 알림 채널. 본문은 새 버전 하나이고, 받는 쪽은 그걸 보고 스냅샷을 다시 읽는다.
     *
     * <p>⚠️ {@link #PUBSUB_PREFIX} 아래에 두면 안 된다 — 방 채널 구독이 그 접두 뒤를
     * {@code UUID} 로 파싱하므로, 이름이 그 밑에 있으면 전 Pod 가 알림마다 파싱 실패를 찍고
     * 봉투를 버린다. {@link #accountEvents} 와 같은 이유로 형제 자리에 둔다.
     *
     * <p><b>이 알림에 사전 내용을 싣지 않는다.</b> Pub/Sub 은 비영속이라 놓친 메시지가 돌아오지
     * 않고, 내용을 실으면 놓친 Pod 이 영구히 다른 사전으로 집행한다. 버전만 실으면 그 Pod 이
     * 주기 확인에서 스스로 복구한다 — 알림은 <b>빠른 길</b>이고 정본은 스냅샷이다.
     */
    static String dictChanged() {
        return "chat:dict:changed";
    }

    /**
     * 전송 레이트리밋 키 — 값은 다음 허용 시각(ms), 수명은 그 시각까지다({@code RedisRateLimiter}).
     * 면제는 운영자와 이 방을 진행하는 판매자뿐 — 남의 방 판매자도 대상이다.
     */
    static String rateLimit(UUID userId) {
        return "ratelimit:chat:" + userId;
    }

    /** Pod 간 채팅 중계 Pub/Sub 채널 prefix — 발행 키·구독 패턴·채널명 파싱의 단일 소스(drift 방지). */
    static final String PUBSUB_PREFIX = "chat:pubsub:";

    /** Pod 간 채팅 중계 Pub/Sub 채널 — CHAT·KICK_EVENT 봉투를 함께 실어 나른다 */
    static String pubsub(UUID roomId) {
        return PUBSUB_PREFIX + roomId;
    }

    /** 전 방 패턴 구독용 (RedisChatBroadcaster 상시 가동 hot 스트림) */
    static String pubsubPattern() {
        return PUBSUB_PREFIX + "*";
    }

    /**
     * 계정 단위 조치 채널 — 방이 아니라 사람에 대한 신호가 여기로 간다.
     *
     * <p><b>{@link #PUBSUB_PREFIX} 아래에 두면 안 된다.</b> 방 채널 구독이 그 접두 뒤를 {@code UUID}로
     * 파싱하므로, 이름이 그 밑에 있으면 전 Pod가 이벤트마다 파싱 실패를 찍고 봉투를 버린다.
     */
    static String accountEvents() {
        return "chat:account:events";
    }

    /**
     * 계정 이벤트 재발행 억제 키 — 존재하면 최근에 이미 알렸다는 뜻이다.
     *
     * <p>발행은 밴이 새로 걸렸든 이미 있던 것이든 나간다(그게 유일한 회수 경로다). 그래서 이미 밴된
     * 대상을 반복 강퇴하면 호출마다 전 Pod가 로컬 세션을 훑는다 — 호출 횟수가 곧 함대 전체 스캔
     * 횟수가 되고, 그 엔드포인트에는 레이트리밋이 없다.
     */
    static String accountEventDebounce(UUID userId) {
        return "chat:account:pub:" + userId;
    }
}
