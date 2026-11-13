package com.sapari.streamingapp.websocket;

/**
 * 입장 게이트가 연결을 거부할 때의 신호. 핸들러가 {@link #reason()}으로 어떤 SYSTEM 메시지를 보낼지
 * 정하고 1008 close 한다. (토큰 검증 실패는 {@code WebSocketAuthException}, 이쪽은 토큰은 유효하나
 * chat 이 거부하는 경우.)
 *
 * <p>사유는 두 종류다 — <b>모더레이션 상태</b>(KICKED·BANNED·ROOM_ENDED)와 <b>남용 상한</b>
 * ({@code TOO_MANY_SESSIONS}). 한때 이 문장이 "chat 소유 모더레이션 상태로 거부되는 경우" 로만
 * 적혀 있었는데, 그러면 {@code TOO_MANY_SESSIONS} 가 스스로 "모더레이션이 아니라 남용 상한" 이라고
 * 적은 것과 이 자리에서 모순된다. 둘을 가르는 것이 실제로 중요하다 — 제재는 사람이 풀어야 하고
 * 남용 상한은 자리가 비면 스스로 풀린다.
 */
public class EntryDeniedException extends RuntimeException {

    public enum Reason {
        KICKED,
        BANNED,
        ROOM_ENDED,

        /** 탈퇴를 신청했다 — 이미 받아 둔 룸 토큰으로 다시 들어오려 했다. 영구다(재접속하지 않는다). */
        WITHDRAWING,

        /**
         * 같은 (유저, 방)의 동시 세션 상한 초과. 모더레이션이 아니라 남용 상한이다 —
         * {@code ChatSessionRegistry.DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM} 필드에 근거가 적혀 있다
         * (왜 (유저, 방) 단위인가 · evict-oldest 를 기각한 이유). 집행하는 값은 설정이고
         * 접근자는 {@code maxSessionsPerUserPerRoom()} 이지만, 근거는 그 필드에 있다.
         */
        TOO_MANY_SESSIONS
    }

    private final Reason reason;

    public EntryDeniedException(Reason reason) {
        super("입장 거부: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
