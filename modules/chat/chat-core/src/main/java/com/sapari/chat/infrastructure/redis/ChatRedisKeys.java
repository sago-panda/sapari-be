package com.sapari.chat.infrastructure.redis;

import java.util.UUID;

/**
 * chat이 소유한 Redis 키 패턴 (§6.1). package-private — 키 문자열이 어댑터 밖으로 새지 않게 한다.
 * room:{roomId}:live·banned:{userId} 등 타 소유(live-core·api-app) 키는 여기 두지 않는다.
 */
final class ChatRedisKeys {

    private ChatRedisKeys() {
    }

    /** sessionId → userId 매핑 HASH (멀티탭 지원, 라이브 종료 시 삭제) */
    static String sessions(UUID roomId) {
        return "room:" + roomId + ":sessions";
    }

    /** 방 강퇴 userId 집합 SET (재접속 차단, 라이브 종료 시 삭제) */
    static String kicked(UUID roomId) {
        return "kicked:" + roomId;
    }

    /** 전송 레이트리밋 키 (3초 TTL, BUYER 전용) */
    static String rateLimit(UUID userId) {
        return "ratelimit:chat:" + userId;
    }

    /** Pod 간 채팅 중계 Pub/Sub 채널 prefix — 발행 키·구독 패턴·채널명 파싱의 단일 소스(drift 방지). */
    static final String PUBSUB_PREFIX = "chat:pubsub:";

    /** Pod 간 채팅 중계 Pub/Sub 채널 — CHAT·KICK_EVENT 봉투를 함께 실어 나른다 (§6.1) */
    static String pubsub(UUID roomId) {
        return PUBSUB_PREFIX + roomId;
    }

    /** 전 방 패턴 구독용 (RedisChatBroadcaster 상시 가동 hot 스트림) */
    static String pubsubPattern() {
        return PUBSUB_PREFIX + "*";
    }
}
