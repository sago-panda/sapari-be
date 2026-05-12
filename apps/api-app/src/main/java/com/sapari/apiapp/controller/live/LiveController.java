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
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.port.CreateLiveFacade;
import com.sapari.live.port.EnterLiveFacade;
import com.sapari.live.port.StartLiveFacade;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.EnterLiveResult;
import com.sapari.live.view.StartLiveResult;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives")
public class LiveController {

    private final CreateLiveFacade createLiveFacade;
    private final StartLiveFacade startLiveFacade;
    private final EnterLiveFacade enterLiveFacade;

    @PostMapping("/rooms")
    public ResponseEntity<CreateLiveView> createRoom(@RequestBody @Valid CreateRoomRequest request,@RequestParam(name = "sellerId") UUID sellerId){
        CreateLiveCommand command = request.toCommand(sellerId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createLiveFacade.execute(command));
    }

    @PostMapping("/rooms/{roomId}/broadcast/start")
    public ResponseEntity<StartLiveResult> startBroadcast(
            @RequestParam(name = "sellerId") UUID sellerId,
            @PathVariable UUID roomId
    ) {
        StartLiveResult result = startLiveFacade.execute(
                new StartLiveCommand(roomId, sellerId)
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<EnterLiveResult> enterRoom(@PathVariable UUID roomId){
        EnterLiveResult result = enterLiveFacade.execute(
                new EnterLiveCommand(roomId)
        );
        return ResponseEntity.ok(result);
    }
}
