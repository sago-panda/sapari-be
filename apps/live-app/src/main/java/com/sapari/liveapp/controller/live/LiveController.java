package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.liveapp.controller.live.dto.StartBroadcastRequest;
import com.sapari.liveapp.controller.live.dto.CreateRoomRequest;
import com.sapari.liveapp.security.LiveUserPrincipal;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.command.GetLiveCommand;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.port.CreateLiveUseCase;
import com.sapari.live.port.EndLiveUseCase;
import com.sapari.live.port.EnterLiveUseCase;
import com.sapari.live.port.GetLiveUseCase;
import com.sapari.live.port.StartLiveUseCase;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.EnterLiveView;
import com.sapari.live.view.GetLiveView;
import com.sapari.live.view.StartLiveView;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives")
public class LiveController {

    private final CreateLiveUseCase createLiveUseCase;
    private final StartLiveUseCase startLiveUseCase;
    private final EnterLiveUseCase enterLiveUseCase;
    private final EndLiveUseCase endLiveUseCase;
    private final GetLiveUseCase getLiveUseCase;

    @PostMapping("/rooms")
    public ResponseEntity<CreateLiveView> createRoom(@RequestBody @Valid CreateRoomRequest request, @CurrentUserId UUID sellerId) {
        CreateLiveCommand command = request.toCommand(sellerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createLiveUseCase.create(command));
    }

    @PostMapping("/rooms/{roomId}/broadcast/start")
    public ResponseEntity<StartLiveView> startBroadcast(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId,
            @RequestBody @Valid StartBroadcastRequest request
    ) {
        StartLiveView result = startLiveUseCase.start(
                new StartLiveCommand(roomId, sellerId, request.toProductEntries())
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<EnterLiveView> enterRoom(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal LiveUserPrincipal principal
    ) {
        // 시청은 공개라 미인증(principal=null, 게스트)도 입장. 인증 회원은 신원을 실어 룸 토큰을 발급받는다.
        EnterLiveCommand command = (principal == null)
                ? new EnterLiveCommand(roomId, null, null, null, null)
                : new EnterLiveCommand(roomId, principal.userId(), principal.role(),
                        principal.nickname(), principal.email());
        return ResponseEntity.ok(enterLiveUseCase.enter(command));
    }

    @GetMapping("/rooms")
    public ResponseEntity<GetLiveView> getRooms() {
        return ResponseEntity.ok(getLiveUseCase.getRooms(GetLiveCommand.defaultMain()));
    }

    @PostMapping("/rooms/{roomId}/broadcast/end")
    public ResponseEntity<Void> endBroadcast(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId
    ) {
        endLiveUseCase.end(new EndLiveCommand(roomId, sellerId));
        return ResponseEntity.noContent().build();
    }
}
