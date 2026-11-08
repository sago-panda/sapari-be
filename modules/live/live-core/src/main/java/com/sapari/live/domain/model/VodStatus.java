package com.sapari.live.domain.model;

/**
 * 다시보기 처리 상태(live_rooms.vod_status). 이 값을 갱신하는 처리는 아직 없다 — 조회에 그대로 보여 줄 뿐이다.
 */
public enum VodStatus {
    NONE, PROCESSING, READY, FAILED
}
