package com.sapari.liveapp.controller.live;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.liveapp.controller.live.dto.StartBroadcastRequest;
import com.sapari.liveapp.controller.live.dto.CreateRoomRequest;
import com.sapari.liveapp.security.LiveUserPrincipal;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.command.GetLiveCommand;
import com.sapari.live.command.PrepareIngressCommand;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.port.CreateLiveUseCase;
import com.sapari.live.port.EndLiveUseCase;
import com.sapari.live.port.EnterLiveUseCase;
import com.sapari.live.port.GetLiveUseCase;
import com.sapari.live.port.GetLiveReplayUseCase;
import com.sapari.live.port.PrepareIngressUseCase;
import com.sapari.live.port.StartLiveUseCase;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.EnterLiveView;
import com.sapari.live.view.GetLiveView;
import com.sapari.live.view.IngressCredentialView;
import com.sapari.live.view.ReplayView;
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
    private final GetLiveReplayUseCase getLiveReplayUseCase;
    private final PrepareIngressUseCase prepareIngressUseCase;

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEnvelope<CreateLiveView> createRoom(@RequestBody @Valid CreateRoomRequest request, @CurrentUserId UUID sellerId) {
        CreateLiveCommand command = request.toCommand(sellerId);
        return ResponseEnvelope.success(createLiveUseCase.create(command));
    }

    @PostMapping("/rooms/{roomId}/broadcast/start")
    public ResponseEnvelope<StartLiveView> startBroadcast(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId,
            @RequestBody @Valid StartBroadcastRequest request
    ) {
        StartLiveView result = startLiveUseCase.start(
                new StartLiveCommand(roomId, sellerId, request.toProductEntries())
        );
        return ResponseEnvelope.success(result);
    }

    /**
     * RTMP 송출 준비 — OBS 등 인코더에 입력할 rtmpUrl·streamKey 를 발급한다(방송 전 Scheduled 방).
     * streamKey 는 자격증명이라 이 응답으로 1회만 전달된다(저장/재조회 없음).
     */
    @PostMapping("/rooms/{roomId}/ingress")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEnvelope<IngressCredentialView> prepareIngress(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId
    ) {
        IngressCredentialView view = prepareIngressUseCase.prepare(
                new PrepareIngressCommand(roomId, sellerId)
        );
        return ResponseEnvelope.success(view);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEnvelope<EnterLiveView> enterRoom(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal LiveUserPrincipal principal
    ) {
        // 시청은 공개라 미인증(principal=null, 게스트)도 입장. 인증 회원은 신원을 실어 룸 토큰을 발급받는다.
        //
        // 발급 상한은 여기에 없다 — EnterRateLimitFilter 가 시큐리티 체인 안에서 먼저 본다.
        // 컨트롤러에 두면 거부 응답을 여기서 조립해야 하고 그것이 apps/AGENTS.md 위반이다
        // ("실패는 던진다, 조립하지 않는다"; 원시 DTO 면제는 성공 본문에 한정된다).
        EnterLiveCommand command = (principal == null)
                ? new EnterLiveCommand(roomId, null, null, null, null)
                : new EnterLiveCommand(roomId, principal.userId(), principal.role(),
                        principal.nickname(), principal.email());
        return ResponseEnvelope.success(enterLiveUseCase.enter(command));
    }

    @GetMapping("/rooms")
    public ResponseEnvelope<GetLiveView> getRooms() {
        return ResponseEnvelope.success(getLiveUseCase.getRooms(GetLiveCommand.defaultMain()));
    }

    @GetMapping("/rooms/{roomId}/replay")
    public ResponseEnvelope<ReplayView> getReplay(@PathVariable UUID roomId) {
        return ResponseEnvelope.success(getLiveReplayUseCase.getReplay(roomId));
    }

    @PostMapping("/rooms/{roomId}/broadcast/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endBroadcast(
            @CurrentUserId UUID sellerId,
            @PathVariable UUID roomId
    ) {
        endLiveUseCase.end(new EndLiveCommand(roomId, sellerId));
    }
}
