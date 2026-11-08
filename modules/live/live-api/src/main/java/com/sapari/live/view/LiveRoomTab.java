package com.sapari.live.view;

/**
 * 판매자에게 보이는 방 상태 묶음(목록 탭·개수·상세의 status). 내부 상태 이름은 계약에 내보내지 않는다.
 *
 * <p>SCHEDULED = 예약·시작 대기(Ready), LIVE = 방송 중·정지(Suspended), ENDED = 종료, CANCELLED = 취소.
 */
public enum LiveRoomTab {
    SCHEDULED, LIVE, ENDED, CANCELLED
}
