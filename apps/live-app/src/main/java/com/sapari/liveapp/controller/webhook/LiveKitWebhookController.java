package com.sapari.liveapp.controller.webhook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.sapari.live.command.LiveWebhookCommand;
import com.sapari.live.port.ProcessLiveWebhookUseCase;

/**
 * LiveKit이 호출하는 webhook 수신 엔드포인트.
 *
 * <p>인증은 Spring Security가 아니라 <b>본문 서명(Authorization 헤더의 LiveKit JWT)</b>으로 한다.
 * 따라서 이 경로는 {@code permitAll}로 열되, 유스케이스가 서명을 검증한다(위조·헤더 누락 시 401).
 *
 * <p>본문은 {@code @RequestBody byte[]}로 한 번에 바인딩하지 않고 {@link #readBounded}로 <b>상한까지만
 * 스트리밍 읽기</b>한다. permitAll 공개 경로라, {@code Transfer-Encoding: chunked}(Content-Length 부재)로
 * 대용량 본문을 보내면 자동 바인딩은 검증 전에 전량을 힙에 적재해 메모리 DoS가 된다. 누적 바이트가 상한을
 * 넘는 순간 즉시 중단·거부(413)해 그 이상 버퍼링하지 않는다. 서명 검증을 위해 <b>원본 바이트 그대로</b>
 * 넘긴다(String 디코딩 시 charset으로 바이트가 달라져 서명이 깨질 수 있음).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks/livekit")
public class LiveKitWebhookController {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final ProcessLiveWebhookUseCase processLiveWebhookUseCase;

    @PostMapping
    public ResponseEntity<Void> receive(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) throws IOException {
        byte[] body = readBounded(request.getInputStream(), MAX_BODY_BYTES);
        processLiveWebhookUseCase.process(new LiveWebhookCommand(body, authHeader));
        return ResponseEntity.ok().build();
    }

    /**
     * 최대 {@code max} 바이트까지만 읽는다. 초과하는 순간 더 읽지 않고 413으로 거부해 힙 적재를 막는다
     * (Content-Length 유무와 무관하게 chunked도 커버).
     */
    private byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > max) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
