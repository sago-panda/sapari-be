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
 *
 * <p>{@code closedByRoomEnd}는 {@code roomEnded}와 다르다. {@code roomEnded}는 "이 방이 끝난 것을 안다"(전송
 * 경로가 확인해도 켜진다)이고, {@code closedByRoomEnd}는 "방 종료 처리({@code closeAll})가 이 세션을 닫았다"다.
 * 뒤의 것만 그 다음 단계 {@code clearRoom}이 명부 키를 지운다는 뜻이라, 떠날 때 필드 제거를 건너뛰는 근거가 된다.
 * 같은 이유로 재등록({@code ViewerCounts.reregister})도 이 표시가 켜진 세션은 다시 쓰지 않는다.
 */
record LocalSession(String sessionId, ChatSession session, Sinks.Many<OutboundFrame> sink,
        Sinks.One<CloseStatus> terminate, AtomicReference<CloseStatus> closeStatus,
        AtomicLong lastRejectionReplyNanos, AtomicLong rateLimitedUntilNanos,
        AtomicLong lastRoomAliveCheckNanos, AtomicBoolean roomEnded,
        AtomicLong lastInboundNanos, AtomicLong inboundAllowedAtNanos, AtomicBoolean closedByRoomEnd) {
}
