package com.sapari.live.port;

/**
 * 공개 방송 중 목록을 원본(DB)과 채팅 시청자 수로 다시 맞춘다 — 주기 잡이 부른다.
 *
 * <p>커밋 후 반영이 놓친 등록·제거를 메우고, 시청자 수(정렬 기준)를 갱신한다. 멱등이라 여러 인스턴스가
 * 동시에 돌아도 결과가 같다.
 */
public interface RefreshLiveListUseCase {
    void refresh();
}
