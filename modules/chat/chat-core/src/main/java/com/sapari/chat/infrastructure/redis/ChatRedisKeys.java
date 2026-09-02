package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

/**
 * chat이 소유한 Redis 키 패턴. package-private — 키 문자열이 어댑터 밖으로 새지 않게 한다.
 *
 * <p>이 패키지의 어댑터가 전부 여기를 거치므로, 읽는 쪽과 쓰는 쪽이 다른 키를 보는 drift가 생기지 않는다.
 * 강퇴 쓰기(SADD)처럼 blocking 스택 어댑터가 나중에 붙어도 <b>같은 패키지</b>에 두면 그대로 공유된다 —
 * 다른 모듈에서 쓰겠다고 public으로 열지 말 것. 키를 아는 코드가 늘어나는 만큼 drift 위험이 늘어난다.
 *
 * <p>밴 상태 키(banned:{userId})는 강퇴 에스컬레이션이 쓰는 chat 소유 키라 여기 들어올 자리다(미구현).
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

    /** 전송 레이트리밋 키 (3초 TTL). 면제는 운영자와 이 방을 진행하는 판매자뿐 — 남의 방 판매자도 대상이다. */
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
}
