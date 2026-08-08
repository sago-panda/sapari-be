package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 키 문자열을 값으로 고정한다.
 *
 * <p>다른 곳의 실수는 대개 예외로 드러나지만 <b>키 오타는 아무 소리도 내지 않는다</b> — 쓰는 쪽과 읽는
 * 쪽이 다른 키를 보면 조회가 그냥 계속 비어서, 강퇴가 안 먹거나 시청자 수가 0으로 나오는 식으로만
 * 나타난다. 그래서 "형태가 그럴듯한지"가 아니라 정확한 문자열을 박아둔다.
 *
 * <p>이 값들은 운영 Redis에 이미 쌓여 있는 데이터의 주소이기도 하다. 바꾸면 배포 순간 기존 키를 전부
 * 잃는다는 뜻이라, 이 테스트가 깨지는 건 "고칠 것"이 아니라 "정말 바꿀 셈인가"를 묻는 신호다.
 */
@DisplayName("ChatRedisKeys — 키 문자열 계약")
class ChatRedisKeysTest {

    private final UUID roomId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("세션 HASH 키")
    void sessions() {
        // when & then
        assertThat(ChatRedisKeys.sessions(roomId))
                .isEqualTo("room:11111111-1111-1111-1111-111111111111:sessions");
    }

    @Test
    @DisplayName("강퇴 SET 키")
    void kicked() {
        // when & then
        assertThat(ChatRedisKeys.kicked(roomId))
                .isEqualTo("kicked:11111111-1111-1111-1111-111111111111");
    }

    @Test
    @DisplayName("방 종료 마커 키")
    void roomEnded() {
        // when & then
        assertThat(ChatRedisKeys.roomEnded(roomId))
                .isEqualTo("room:11111111-1111-1111-1111-111111111111:ended");
    }

    @Test
    @DisplayName("레이트리밋 키는 방이 아니라 유저 단위 — 방을 옮겨도 제한이 따라붙는다")
    void rateLimit() {
        // when & then
        assertThat(ChatRedisKeys.rateLimit(userId))
                .isEqualTo("ratelimit:chat:22222222-2222-2222-2222-222222222222");
    }

    @Test
    @DisplayName("Pub/Sub 채널 키")
    void pubsub() {
        // when & then
        assertThat(ChatRedisKeys.pubsub(roomId))
                .isEqualTo("chat:pubsub:11111111-1111-1111-1111-111111111111");
    }

    @Test
    @DisplayName("발행 채널·구독 패턴·채널명 파싱이 같은 prefix를 쓴다 — 하나만 어긋나도 중계가 통째로 끊긴다")
    void prefixIsShared() {
        // when & then: 구독자는 패턴으로 붙고 발행자는 채널명으로 쏘며, 수신 측은 prefix를 잘라 roomId를
        // 되찾는다. 세 경로가 같은 상수를 보지 않으면 메시지는 나가지만 아무도 못 받는다.
        assertThat(ChatRedisKeys.pubsub(roomId)).startsWith(ChatRedisKeys.PUBSUB_PREFIX);
        assertThat(ChatRedisKeys.pubsubPattern()).isEqualTo(ChatRedisKeys.PUBSUB_PREFIX + "*");
        assertThat(ChatRedisKeys.pubsub(roomId).substring(ChatRedisKeys.PUBSUB_PREFIX.length()))
                .isEqualTo(roomId.toString());
    }

    @Test
    @DisplayName("같은 방의 서로 다른 키가 겹치지 않는다 — 겹치면 WRONGTYPE으로 그 방 기능 하나가 죽는다")
    void keysDoNotCollide() {
        // when & then: room:{id}:sessions(HASH)와 room:{id}:ended(String)는 접두가 같아서
        // 접미가 사라지는 순간 타입이 충돌한다. 그때 나는 건 조회 실패가 아니라 영구 에러다.
        assertThat(ChatRedisKeys.sessions(roomId))
                .isNotEqualTo(ChatRedisKeys.roomEnded(roomId))
                .isNotEqualTo(ChatRedisKeys.kicked(roomId))
                .isNotEqualTo(ChatRedisKeys.pubsub(roomId));
        assertThat(ChatRedisKeys.roomEnded(roomId)).isNotEqualTo(ChatRedisKeys.kicked(roomId));
    }

    @Test
    @DisplayName("방이 다르면 키도 다르다 — 방 격리의 물리적 근거")
    void keysAreScopedByRoom() {
        // given
        UUID otherRoom = UUID.fromString("99999999-9999-9999-9999-999999999999");

        // when & then
        assertThat(ChatRedisKeys.sessions(roomId)).isNotEqualTo(ChatRedisKeys.sessions(otherRoom));
        assertThat(ChatRedisKeys.kicked(roomId)).isNotEqualTo(ChatRedisKeys.kicked(otherRoom));
        assertThat(ChatRedisKeys.roomEnded(roomId)).isNotEqualTo(ChatRedisKeys.roomEnded(otherRoom));
        assertThat(ChatRedisKeys.pubsub(roomId)).isNotEqualTo(ChatRedisKeys.pubsub(otherRoom));
    }
}
