package com.sapari.apiapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.apiapp.controller.dto.CreateRoomRequest;
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.port.CreateLiveFacade;
import com.sapari.live.view.CreateLiveView;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/live")
public class LiveController {

    private final CreateLiveFacade createLiveFacade;

    @PostMapping
    public ResponseEntity<CreateLiveView> createRoom(@RequestBody @Valid CreateRoomRequest request,@RequestParam(name = "sellerId") UUID sellerId){
        CreateLiveCommand command = request.toCommand(sellerId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createLiveFacade.execute(command));
    }
}
