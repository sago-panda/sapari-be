package com.sapari.live.port;

import com.sapari.live.command.ChangeSellerLivePermissionCommand;

/**
 * 관리자가 판매자의 라이브 권한을 켜고 끈다. 꺼진 판매자는 방 생성·방송 시작·송출 키 발급/재발급이 막히고,
 * 이미 방송 중인 방은 그대로 둔다(끊으려면 {@link AdminEndLiveUseCase}).
 *
 * <p>ADMIN 판정은 여기서 하지 않는다 — 경로 단위로 막는다({@code LiveSecurityConfig}).
 */
public interface ChangeSellerLivePermissionUseCase {
    void change(ChangeSellerLivePermissionCommand command);
}
