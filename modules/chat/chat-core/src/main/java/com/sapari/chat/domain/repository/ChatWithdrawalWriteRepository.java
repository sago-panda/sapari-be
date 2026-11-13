package com.sapari.chat.domain.repository;

import java.util.UUID;

import com.sapari.chat.port.ReactiveChat;

import reactor.core.publisher.Mono;

/**
 * 탈퇴 신청 기록 쓰기 — 탈퇴 이벤트 구독({@code AccountWithdrawingHandler})이 쓴다.
 *
 * <p>기록에는 수명이 있고, 그 수명은 <b>탈퇴 전에 발급된 룸 토큰이 살아 있을 수 있는 시간 이상</b>이어야
 * 한다. 짧으면 기록이 먼저 사라지고 남은 토큰으로 다시 들어온다 — 덮어야 할 창보다 먼저 만료되는 마커다.
 *
 * <p>모든 Pod 가 같은 이벤트를 받으므로 같은 기록을 여러 번 쓴다. 같은 값과 같은 수명이라 해롭지 않고,
 * 한 Pod 가 이벤트를 놓쳐도 다른 Pod 의 쓰기가 남는다.
 */
@ReactiveChat
public interface ChatWithdrawalWriteRepository {

    Mono<Void> markWithdrawing(UUID userId);
}
