package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 탈퇴 신청 기록 조회 포트 — 입장 게이트가 쓴다. 리액티브(streaming-app).
 *
 * <p><b>왜 필요한가.</b> 룸 토큰 검증은 핸드셰이크에서 서명과 만료만 본다. 탈퇴 신청 뒤에도 이미 받아 둔
 * 토큰은 만료 전까지 유효하고, 그 토큰으로 맺은 연결은 토큰의 만료가 아니라 세션 수명까지 산다.
 * 탈퇴 이벤트가 끊는 것은 <b>그 순간 열려 있던</b> 세션뿐이라, 이 기록이 없으면 끊긴 직후 같은 토큰으로
 * 다시 들어올 수 있다.
 *
 * <p><b>어댑터 계약</b>: Redis 장애 시 {@code false}로 흡수하지 말고 error를 전파한다. 소비처 정책은
 * 입장 게이트에서만 읽고, 그 자리의 다른 조회와 같은 fail-open 이다({@link ChatBanRepository}).
 */
public interface ChatWithdrawalRepository {

    Mono<Boolean> isWithdrawing(UUID userId);
}
