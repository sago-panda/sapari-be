package com.sapari.live.port;

import com.sapari.live.command.AdminEndLiveCommand;

/**
 * 관리자 종료 — 방 주인이 아닌 관리자가 사유를 남기고 방송 중인 방을 끊는다.
 * 결과는 {@code Cancelled}(시작·취소 시각 보존, 다시보기 없음)이고, 누가 왜 끊었는지는 감사 기록에만 남는다.
 *
 * <p>ADMIN 판정은 여기서 하지 않는다 — 경로 단위로 막는다({@code LiveSecurityConfig}).
 */
public interface AdminEndLiveUseCase {
    void end(AdminEndLiveCommand command);
}
