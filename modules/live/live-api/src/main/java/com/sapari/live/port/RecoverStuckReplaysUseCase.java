package com.sapari.live.port;

/**
 * 녹화 종료 알림(egress_ended)을 놓쳐 READY 가 못 된 종료 방의 다시보기를 살린다. 영상은 이미 저장소에 있고,
 * 빠진 것은 상태 표시뿐이다. 웹훅 핸들러는 실패해도 200 을 주고 createdAt 창이 있어 같은 알림이 다시 처리되지
 * 않으므로, 이 경로가 없으면 그 다시보기는 영구히 숨는다.
 */
public interface RecoverStuckReplaysUseCase {

    /** @return READY 로 올린 방 수 */
    int recover();
}
