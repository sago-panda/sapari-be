package com.sapari.chat.domain.exception;

/**
 * 연산 상한이 메시지 저장을 끊었다 — <b>아무것도 남지 않았다</b>.
 *
 * <p><b>일시 장애와 갈라 두는 타입이다.</b> 재시도는 다음 요청에 나을 수 있지만, 이 경우의 성질은
 * "느려서 못 썼다" 라 사용자에게 보이는 결과가 다른 저장 실패와 같아도 <b>운영 판독이 다르다</b> —
 * 느린 Mongo 를 가리키는 신호이기 때문이다. 같은 예외로 뭉뚱그리면 <b>발신자에게 가는
 * 에러코드</b>가 다른 저장 실패와 같아진다 — 지표는 이 타입과 무관하게 어댑터가
 * {@code doOnError} 로 센다(SPR-178).
 *
 * <p>사용자 잘못이 아니라 서버 쪽 상태라 5xx({@link ChatErrorCode#PERSIST_TIMEOUT})다.
 * <b>흡수하지 않는다</b> — 저장이 안 됐으므로 ack 를 주면 "유실이 성공" 으로 보고된다.
 *
 * <p>번역이 <b>어댑터</b>에서 일어나는 이유. 판정에 드라이버 타입이 필요한데 루트 {@code AGENTS.md}
 * 가 서비스의 SDK 직접 사용을 금지하고, ArchUnit 의
 * {@code application_must_not_depend_on_infrastructure} 가 그 반대 방향도 막는다. 그래서 상한을 아는
 * 쪽(어댑터)이 이 타입으로 바꿔 올린다 — 같은 파일이 {@code DuplicateKeyException} 을 받는 방식과
 * 같은 층이다.
 */
public class ChatPersistTimeoutException extends ChatException {

    public ChatPersistTimeoutException(Throwable cause) {
        super(ChatErrorCode.PERSIST_TIMEOUT, "메시지 저장이 연산 상한에서 끊겼다", cause);
    }
}
