package com.sapari.streamingapp.websocket;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.reactive.socket.CloseStatus;

import com.sapari.chat.domain.model.ChatSession;

import reactor.core.publisher.Sinks;

/**
 * 한 커넥션의 채널 묶음.
 *
 * <p><b>{@code sink}(데이터) 와 {@code terminate}(제어)를 나눈 이유</b>: 종료를 sink complete로만 알리면
 * 그 신호가 버퍼에 쌓인 메시지 <i>뒤에</i> 줄을 선다. 소켓을 읽지 않는 클라에겐 영영 닿지 않아 연결·세션
 * 항목·방 구독이 회수되지 않는다. 제어 신호는 데이터 큐를 타지 않아야 한다.
 *
 * <p>{@code closeStatus}는 종료 사유 — complete로 곱게 닫히든 제어 채널로 끊기든 같은 코드로 닫기 위해
 * 따로 보관한다(complete 신호엔 코드를 실을 수 없다).
 */
record LocalSession(String sessionId, ChatSession session, Sinks.Many<OutboundFrame> sink,
        Sinks.One<CloseStatus> terminate, AtomicReference<CloseStatus> closeStatus,
        AtomicLong lastRejectionReplyNanos, AtomicLong rateLimitedUntilNanos,
        AtomicLong lastRoomAliveCheckNanos, AtomicBoolean roomEnded,
        AtomicLong lastInboundNanos, AtomicLong inboundAllowedAtNanos) {
}
