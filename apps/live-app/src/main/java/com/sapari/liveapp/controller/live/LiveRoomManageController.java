package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.common.page.CursorPage;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.command.CancelLiveCommand;
import com.sapari.live.command.GetSellerRoomDetailCommand;
import com.sapari.live.command.GetSellerRoomsCommand;
import com.sapari.live.port.CancelLiveUseCase;
import com.sapari.live.port.GetLiveRoomDetailUseCase;
import com.sapari.live.port.GetSellerRoomsUseCase;
import com.sapari.live.port.UpdateLiveUseCase;
import com.sapari.live.view.LiveRoomTab;
import com.sapari.live.view.PublicLiveRoomDetailView;
import com.sapari.live.view.SellerLiveRoomCountsView;
import com.sapari.live.view.SellerLiveRoomDetailView;
import com.sapari.live.view.SellerLiveRoomSummary;
import com.sapari.liveapp.controller.live.dto.UpdateRoomRequest;

/**
 * 방 관리 — 취소·수정·판매자 내 방 목록·상세(SPR-158).
 *
 * <p><b>판매자 조회는 {@code /seller/rooms/**} 아래다.</b> {@code GET /rooms/{한 세그먼트}} 는 공개(permitAll)이자
 * 입장 상한({@code EnterPaths}) 대상이라, 거기에 판매자 경로({@code /rooms/mine} 등)를 두면 공개로 열린다.
 * {@code /seller/**} 는 {@code /api/v1/lives/**} 의 SELLER 바닥 규칙에만 걸린다.
 *
 * <p>공개 상세({@code /rooms/{roomId}/detail})는 permitAll 에 명시돼 있고 입장 상한 밖이다 — 그 상한은
 * 룸 토큰 서명 비용의 상한인데 상세에는 서명이 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives")
public class LiveRoomManageController {

    private final CancelLiveUseCase cancelLiveUseCase;
    private final UpdateLiveUseCase updateLiveUseCase;
    private final GetSellerRoomsUseCase getSellerRoomsUseCase;
    private final GetLiveRoomDetailUseCase getLiveRoomDetailUseCase;

    @PostMapping("/rooms/{roomId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRoom(@CurrentUserId UUID sellerId, @PathVariable UUID roomId) {
        cancelLiveUseCase.cancel(new CancelLiveCommand(roomId, sellerId));
    }

    @PutMapping("/rooms/{roomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRoom(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId,
            @RequestBody @Valid UpdateRoomRequest request
    ) {
        updateLiveUseCase.update(request.toCommand(roomId, sellerId));
    }

    @GetMapping("/rooms/{roomId}/detail")
    public ResponseEnvelope<PublicLiveRoomDetailView> getPublicDetail(@PathVariable UUID roomId) {
        return ResponseEnvelope.success(getLiveRoomDetailUseCase.getPublicDetail(roomId));
    }

    @GetMapping("/seller/rooms")
    public ResponseEnvelope<CursorPage<SellerLiveRoomSummary>> getSellerRooms(
            @CurrentUserId UUID sellerId,
            @RequestParam(required = false) LiveRoomTab tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEnvelope.success(
                getSellerRoomsUseCase.getRooms(new GetSellerRoomsCommand(sellerId, tab, cursor, size)));
    }

    @GetMapping("/seller/rooms/counts")
    public ResponseEnvelope<SellerLiveRoomCountsView> getSellerRoomCounts(@CurrentUserId UUID sellerId) {
        return ResponseEnvelope.success(getSellerRoomsUseCase.getCounts(sellerId));
    }

    @GetMapping("/seller/rooms/{roomId}")
    public ResponseEnvelope<SellerLiveRoomDetailView> getSellerDetail(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId
    ) {
        return ResponseEnvelope.success(
                getLiveRoomDetailUseCase.getSellerDetail(new GetSellerRoomDetailCommand(roomId, sellerId)));
    }
}
