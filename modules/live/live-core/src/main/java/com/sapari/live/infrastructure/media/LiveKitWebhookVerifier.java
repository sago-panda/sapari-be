package com.sapari.live.infrastructure.media;

import io.livekit.server.WebhookReceiver;
import livekit.LivekitWebhook.WebhookEvent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveWebhookEvent;
import com.sapari.live.application.port.WebhookVerifier;
import com.sapari.live.domain.exception.InvalidWebhookException;

/**
 * LiveKit SDK {@link WebhookReceiver}로 webhook 서명을 검증하는 어댑터.
 *
 * <p>{@code Authorization} 헤더의 JWT 서명을 LiveKit apiKey/secret으로 검증한다. 서명 불일치·파싱 실패는
 * {@link InvalidWebhookException}으로 변환해 위조 요청을 거부한다. 검증된 이벤트는 SDK 타입을 감춘
 * {@link LiveWebhookEvent}로 변환해 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitWebhookVerifier implements WebhookVerifier {

    // 본문 상한은 컨트롤러의 bounded read가 강제한다(chunked 포함). 여기서는 포트가 직접 호출되는 경우까지
    // 대비한 방어적 상한이며, 이것이 유일한 메모리 DoS 방어는 아니다.
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /**
     * 이벤트 신선도 창(과거). SDK 의 서명 검증만으로는 재전송을 막지 못한다 — auth0 {@code JWTVerifier} 는
     * {@code exp} 가 <b>없으면 그냥 통과</b>시키고, {@code WebhookReceiver} 는 그 클레임을 요구하지 않는다.
     * 캡처한 요청은 서명이 영원히 유효하므로 {@code createdAt} 으로 창을 건다.
     *
     * <p>재전송은 <b>원본 {@code createdAt} 을 유지</b>하므로, 우리가 잠깐 죽어 있는 동안(롤링 배포 등)
     * 쌓인 재전송까지 이 창 안에 들어와야 한다. 짧게 잡으면 정상 이벤트가 거부되고 그 방은 정리 잡이
     * 60분 뒤에야 손대게 된다. 지금 핸들러는 {@code ingress_started} 뿐이라 리플레이 최대 피해가
     * "Ready+RTMP 방이 Live 가 됨"(= OBS 가 붙었을 때와 동일)이므로, 창을 넓혀도 잃는 게 크지 않다.
     */
    private static final Duration PAST_WINDOW = Duration.ofMinutes(15);

    /**
     * 미래 허용치. 재전송 방어에 기여하지 않고 창만 넓히므로 <b>시계 오차만큼만</b> 준다.
     * 과거 창과 같은 값을 주면 공격자가 쓸 수 있는 구간이 두 배가 된다.
     */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(60);

    private final WebhookReceiver webhookReceiver;
    private final TimeProvider timeProvider;

    @Override
    public LiveWebhookEvent verify(byte[] body, String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw reject("missing-auth-header");
        }
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw reject("body-size");
        }
        WebhookEvent event;
        try {
            // 원본 바이트를 UTF-8로 명시 변환 — SDK가 UTF-8 기준으로 body sha256을 계산하므로 charset 불일치 방지.
            String payload = new String(body, StandardCharsets.UTF_8);
            event = webhookReceiver.receive(payload, authHeader);
        } catch (Exception e) {
            // 서명 불일치·파싱 실패 등 — 원본 값은 담지 않고 도메인 예외로 변환.
            log.warn("LiveKit webhook 거부 — 사유=signature-or-parse");
            throw new InvalidWebhookException(e);
        }
        // 신선도 검사는 try 밖에 둔다 — 안에 두면 자기가 던진 예외를 아래 catch 가 다시 감싼다(cause=자기자신).
        requireFresh(event);
        return new LiveWebhookEvent(
                event.getEvent(),
                resolveRoomName(event),
                event.hasIngressInfo() ? event.getIngressInfo().getIngressId() : null,
                event.hasEgressInfo() ? event.getEgressInfo().getEgressId() : null
        );
    }

    /**
     * 거부 사유를 로그로만 구분한다 — 호출자에게는 전부 같은 예외를 준다(어느 검사에서 걸렸는지 알려주면
     * 위조 시도의 오라클이 된다). 시계 드리프트로 전량 차단됐을 때 원인을 가릴 수단이 이 로그뿐이다.
     */
    private InvalidWebhookException reject(String reason) {
        log.warn("LiveKit webhook 거부 — 사유={}", reason);
        return new InvalidWebhookException();
    }

    /**
     * 발생 시각이 {@link #PAST_WINDOW}/{@link #FUTURE_SKEW} 를 벗어나면 거부한다(재전송 방어).
     *
     * <p>{@code createdAt} 이 없으면(0) <b>거부</b>한다 — 판정할 수 없는데 통과시키면 통제가 있는 척만 하고
     * 실제로는 뚫려 있다. 다만 <b>LiveKit 서버가 이 필드를 채우는지는 저장소에서 확인할 수 없다</b> —
     * 안 채우면 webhook 이 전량 막혀 방이 Ready 에 갇힌다. <b>스테이징에서 실제 이벤트 1건을 흘려보는 것을
     * 배포 게이트로 둘 것.</b>
     *
     * <p>미래 시각도 거부하되 허용치는 시계 오차 수준으로만 준다 — 넉넉히 주면 창만 넓어진다.
     */
    private void requireFresh(WebhookEvent event) {
        long createdAtSeconds = event.getCreatedAt();
        // 거부 자체는 아래 stale 검사가 이미 해준다(0 = 1970 = 창 밖). 이 분기는 로그 사유를 가르기 위해 둔다 —
        // 전량 차단이 "시계 드리프트"인지 "서버가 createdAt 을 안 채움"인지 구분할 유일한 수단이다.
        if (createdAtSeconds <= 0) {
            throw reject("missing-created-at");
        }
        Instant createdAt = Instant.ofEpochSecond(createdAtSeconds);
        Instant now = timeProvider.now();
        // 원본 값을 함께 남긴다 — LiveKit 이 초가 아니라 밀리초를 보내면 전 이벤트가 future-timestamp 로
        // 거부돼 방이 Ready 에 갇히는데, 사유만 봐서는 시계 오차와 구분되지 않는다. 값이 찍혀 있으면
        // 자릿수만 보고 1분 안에 판별된다(비밀이 아니라 발생 시각이므로 로그에 남겨도 된다).
        if (createdAt.isBefore(now.minus(PAST_WINDOW)) || createdAt.isAfter(now.plus(FUTURE_SKEW))) {
            log.warn("LiveKit webhook createdAt 창 밖 — createdAt={}(raw={}), now={}", createdAt, createdAtSeconds, now);
            throw reject(createdAt.isBefore(now) ? "stale" : "future-timestamp");
        }
    }

    /**
     * 이벤트가 가리키는 방 이름을 뽑는다. room 이벤트(room_finished 등)는 {@code room}에,
     * ingress 이벤트(ingress_started 등)는 {@code ingressInfo.roomName}에 담기므로 room 우선·ingress fallback.
     * (proto 기본값은 빈 문자열이라 blank 는 미설정으로 간주.)
     */
    private String resolveRoomName(WebhookEvent event) {
        if (event.hasRoom() && !event.getRoom().getName().isBlank()) {
            return event.getRoom().getName();
        }
        if (event.hasIngressInfo() && !event.getIngressInfo().getRoomName().isBlank()) {
            return event.getIngressInfo().getRoomName();
        }
        return null;
    }
}
