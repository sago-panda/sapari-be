package com.sapari.live.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

final class LiveRedisKeys {

    private LiveRedisKeys() {}

    static String room(UUID roomId) {
        return rawRoom(roomId.toString());
    }

    /** 랭킹 구성원 문자열 그대로 — 정리 대상은 UUID 로 읽히지 않는 잔재일 수도 있다. */
    static String rawRoom(String roomId) {
        return "live:room:" + roomId;
    }

    static String ranking() {
        return "live:ranking";
    }

    /**
     * 방 종료 키 — Pub/Sub 을 놓친 쪽(chat)이 종료를 확인하기 위한 자리다(SPR-185).
     *
     * <p>chat 은 SPR-184 부터 자기 마커({@code chat:room:{id}:ended})와 이 키를 함께 읽는다
     * ({@code ChatRoomEndedRedisRepository.isEnded}).
     *
     * <p><b>chat 과의 계약:</b>
     * <ul>
     *   <li>값: 종료 시각 epoch millis 문자열 — 이벤트 payload 의 {@code endedAt} 과 같은 수.</li>
     *   <li>TTL: {@link #ROOM_ENDED_TTL}(70분). 하한은 chat 세션 최대 수명(기본 60분)이다 — 종료 전에 열린
     *       세션은 종료 뒤에도 그만큼 살 수 있고, 그동안 키가 남아 있어야 한다. ⚠️ chat 의
     *       {@code ChatRoomEndedRedisRepository.ENDED_TTL} 을 <b>복사한 수</b>다 — live 는 chat-core 를 볼 수
     *       없어 연동 검사가 없다. chat 이 세션 수명을 올리면 이것도 손으로 올려야 한다.</li>
     *   <li>기록 시점: 종료 커밋 뒤의 {@code afterCommit}, <b>미디어 정리 다음</b>·이벤트 발행 직전.
     *       그래서 키가 있으면 그 방은 DB 에서 이미 {@code Ended} 이고(오판 없음), 이벤트를 받은 쪽이 바로
     *       읽어도 키가 보인다. 커밋부터 기록까지는 미디어 정리(LiveKit 호출당 최대 15s)만큼 늦다.</li>
     * </ul>
     * 기록은 best-effort 다 — 실패하거나 커밋 직후 Pod 가 죽으면 키가 없다. 키가 없다는 것이 진행 중이라는
     * 증거는 아니다.
     */
    static String roomEnded(UUID roomId) {
        return "live:room:ended:" + roomId;
    }

    /** {@link #roomEnded} 보관 기간 — 근거는 그쪽 javadoc. */
    static final Duration ROOM_ENDED_TTL = Duration.ofMinutes(70);

    /** 방 종료 Pub/Sub 채널 (chat이 구독) — 계약: payload {roomId, endedAt}. */
    static String roomEndedChannel() {
        return "live:room:ended";
    }
}
