package com.sapari.live.application.port;

/**
 * 방 종료를 바깥(chat)에 알리는 두 신호 — 지표가 어느 쪽이 실패했는지 가른다.
 *
 * <p>⚠️ <b>지금은 {@link #EVENT} 하나만 실패해도 chat 은 종료를 모른다</b> — 종료 키를 읽는 쪽이 아직
 * 없다({@code LiveRedisKeys.roomEnded}). chat 이 키를 읽기 시작하면(후속 작업) 둘은 서로를 대신한다:
 * {@link #EVENT} 만 실패하면 키로, {@link #MARKER} 만 실패하면 그 순간 구독 중이던 쪽은 이벤트로 안다.
 * 그때부터는 두 태그가 같은 방에서 함께 오를 때가 "chat 이 종료를 모르는 방" 이다.
 */
public enum RoomEndedSignal {
    /** {@code live:room:ended:{roomId}} 종료 키 기록. */
    MARKER,
    /** {@code live:room:ended} Pub/Sub 발행. */
    EVENT
}
