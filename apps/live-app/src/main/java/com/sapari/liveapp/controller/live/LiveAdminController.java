package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.page.OffsetPage;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.command.GetAdminRoomsQuery;
import com.sapari.live.port.AdminEndLiveUseCase;
import com.sapari.live.port.ChangeSellerLivePermissionUseCase;
import com.sapari.live.port.GetAdminRoomsUseCase;
import com.sapari.live.view.AdminLiveRoomSummary;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.liveapp.controller.live.dto.AdminEndLiveRequest;
import com.sapari.liveapp.controller.live.dto.AdminSellerLivePermissionRequest;

/** 관리자 전용 라이브 조치. ADMIN 판정은 경로 단위({@code LiveSecurityConfig})다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives/admin")
public class LiveAdminController {

    private final AdminEndLiveUseCase adminEndLiveUseCase;
    private final GetAdminRoomsUseCase getAdminRoomsUseCase;
    private final ChangeSellerLivePermissionUseCase changeSellerLivePermissionUseCase;

    /** 전체 방 목록(SPR-212). 기간은 예정 시각 기준 [from, to), ISO-8601 instant 다. */
    @GetMapping("/rooms")
    public ResponseEnvelope<OffsetPage<AdminLiveRoomSummary>> getRooms(
            @RequestParam(required = false) LiveRoomTab status,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEnvelope.success(getAdminRoomsUseCase.getRooms(
                new GetAdminRoomsQuery(status, sellerId, from, to, page, size)));
    }

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
