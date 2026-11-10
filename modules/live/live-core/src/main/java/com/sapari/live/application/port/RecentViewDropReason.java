package com.sapari.live.application.port;

/**
 * 최근 본 기록을 버린 이유. 두 갈래의 대응이 반대라 태그를 가른다.
 *
 * <ul>
 *   <li>{@link #REJECTED} — 기록 대기열이 가득했다. 입장 폭주가 쓰기 처리량을 넘었다는 뜻이고, DB 는 멀쩡할 수 있다
 *       (대기열·워커 증설을 볼 신호).</li>
 *   <li>{@link #FAILED} — 쓰기가 예외로 끝났다. DB 를 볼 신호.</li>
 * </ul>
 */
public enum RecentViewDropReason {
    REJECTED,
    FAILED
}
