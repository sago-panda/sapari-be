package com.sapari.liveapp.controller.webhook;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.live.command.LiveWebhookCommand;
import com.sapari.live.port.ProcessLiveWebhookUseCase;

/**
 * LiveKit이 호출하는 webhook 수신 엔드포인트.
 *
 * <p>인증은 Spring Security가 아니라 <b>본문 서명(Authorization 헤더의 LiveKit JWT)</b>으로 한다.
 * 따라서 이 경로는 {@code permitAll}로 열되, 유스케이스가 서명을 검증한다(위조·헤더 누락 시 401).
 *
 * <p>본문은 서명 검증을 위해 <b>원본 바이트({@code byte[]})</b>로 받는다 — String으로 받으면 컨테이너의
 * charset 디코딩으로 바이트가 달라져 서명이 깨질 수 있다. Authorization 헤더 누락도 서명 검증 실패(401)와
 * 일관되게 처리하기 위해 {@code required=false}로 받아 verifier가 거부하게 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks/livekit")
public class LiveKitWebhookController {

    private final ProcessLiveWebhookUseCase processLiveWebhookUseCase;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) {
        processLiveWebhookUseCase.process(new LiveWebhookCommand(body, authHeader));
        return ResponseEntity.ok().build();
    }
}
