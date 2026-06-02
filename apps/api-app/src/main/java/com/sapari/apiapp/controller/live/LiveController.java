package com.sapari.apiapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.apiapp.controller.dto.CreateRoomRequest;
import com.sapari.apiapp.controller.dto.StartBroadcastRequest;
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
import com.sapari.live.view.EnterLiveResult;
import com.sapari.live.view.GetLiveResult;
import com.sapari.live.view.StartLiveResult;

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
    public ResponseEntity<CreateLiveView> createRoom(@RequestBody @Valid CreateRoomRequest request, @RequestParam(name = "sellerId") UUID sellerId) {
        CreateLiveCommand command = request.toCommand(sellerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createLiveUseCase.create(command));
    }

    @PostMapping("/rooms/{roomId}/broadcast/start")
    public ResponseEntity<StartLiveResult> startBroadcast(
            @RequestParam(name = "sellerId") UUID sellerId,
            @PathVariable UUID roomId,
            @RequestBody @Valid StartBroadcastRequest request
    ) {
        StartLiveResult result = startLiveUseCase.start(
                new StartLiveCommand(roomId, sellerId, request.toProductEntries())
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<EnterLiveResult> enterRoom(@PathVariable UUID roomId) {
        EnterLiveResult result = enterLiveUseCase.enter(new EnterLiveCommand(roomId));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/rooms")
    public ResponseEntity<GetLiveResult> getRooms() {
        return ResponseEntity.ok(getLiveUseCase.getRooms(GetLiveCommand.defaultMain()));
    }

    @PostMapping("/rooms/{roomId}/broadcast/end")
    public ResponseEntity<Void> endBroadcast(
            @RequestParam(name = "sellerId") UUID sellerId,
            @PathVariable UUID roomId
    ) {
        endLiveUseCase.end(new EndLiveCommand(roomId, sellerId));
        return ResponseEntity.noContent().build();
    }
}
