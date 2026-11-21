package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.port.AdminEndLiveUseCase;
import com.sapari.live.port.ChangeSellerLivePermissionUseCase;
import com.sapari.liveapp.controller.live.dto.AdminEndLiveRequest;
import com.sapari.liveapp.controller.live.dto.AdminSellerLivePermissionRequest;

/** 관리자 전용 라이브 조치. ADMIN 판정은 경로 단위({@code LiveSecurityConfig})다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives/admin")
public class LiveAdminController {

    private final AdminEndLiveUseCase adminEndLiveUseCase;
    private final ChangeSellerLivePermissionUseCase changeSellerLivePermissionUseCase;

    @PostMapping("/rooms/{roomId}/force-end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forceEnd(
            @CurrentUserId UUID adminId,
            @PathVariable UUID roomId,
            @Valid @RequestBody AdminEndLiveRequest request
    ) {
        adminEndLiveUseCase.end(request.toCommand(roomId, adminId));
    }

    @PatchMapping("/sellers/{sellerId}/live-permission")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeSellerLivePermission(
            @CurrentUserId UUID adminId,
            @PathVariable UUID sellerId,
            @Valid @RequestBody AdminSellerLivePermissionRequest request
    ) {
        changeSellerLivePermissionUseCase.change(request.toCommand(sellerId, adminId));
    }
}
