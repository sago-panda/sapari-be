package com.sapari.live.domain.exception;

/**
 * 방송 시작(송출 개시) 처리 중 서버 내부 전제 조건이 깨졌을 때 던진다.
 *
 * <p>도메인 조건이 아니라 "발생해서는 안 되는" 프로그래밍/인프라 불변식 위반이다 — 예: egress 롤백 보상 훅을
 * 등록할 트랜잭션 동기화가 비활성(정상적으로는 {@code @Transactional}이 보장). 5xx 로 응답하며, 상세 원인은
 * debugMessage 로 로그에만 남고 클라이언트에는 errorCode 카탈로그 메시지만 나간다.
 */
public class BroadcastStartException extends LiveDomainException {

    public BroadcastStartException(String debugMessage) {
        super(LiveErrorCode.BROADCAST_START_FAILED, debugMessage);
    }
}
