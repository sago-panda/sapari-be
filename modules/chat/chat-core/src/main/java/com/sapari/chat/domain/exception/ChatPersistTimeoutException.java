package com.sapari.chat.domain.exception;

/**
 * 연산 상한이 메시지 저장을 끊었다 — <b>결과를 알 수 없다</b>.
 *
 * <p>⚠️ <b>"아무것도 남지 않았다" 가 아니다.</b> 한때 이 자리가 그렇게 단정했고 거짓이었다 —
 * 클라이언트 쪽 상한은 <b>서버의 쓰기를 취소하지 않는다</b>. 실측(mongo:7.0 · 드라이버 5.6.5,
 * 상한을 핸들 단위로만 줄임): 1ms·2ms 에서는 예외 + 문서 <b>0건</b>, 3ms·5ms 에서는 예외 +
 * 그 {@code _id} 로 문서 <b>1건</b>. 그리고 <b>두 갈래가 같은 예외 클래스</b>라 호출부는
 * "안 썼다" 와 "썼는데 확인 못 했다" 를 가를 수 없다.
 *
 * <p>그래서 이 예외의 뜻은 <b>"저장 결과 미확인"</b> 이다. 같은 {@code clientMsgId} 로 다시
 * 보내는 것이 안전하다 — {@code (roomId, senderId, clientMsgId)} 유니크 파셜 인덱스가 중복을
 * 막는다(SPR-178). <b>새 {@code clientMsgId} 로 재전송하면 중복이 생길 수 있다.</b>
 *
 * <p><b>일시 장애와 갈라 두는 타입이다.</b> 재시도는 다음 요청에 나을 수 있지만, 이 경우의 성질은
 * "느려서 못 썼다" 라 사용자에게 보이는 결과가 다른 저장 실패와 같아도 <b>운영 판독이 다르다</b> —
 * 느린 Mongo 를 가리키는 신호이기 때문이다. 같은 예외로 뭉뚱그리면 <b>발신자에게 가는
 * 에러코드</b>가 다른 저장 실패와 같아진다 — 지표는 이 타입과 무관하게 어댑터가
 * {@code doOnError} 로 센다(SPR-178).
 *
 * <p>사용자 잘못이 아니라 서버 쪽 상태라 5xx({@link ChatErrorCode#PERSIST_TIMEOUT})다.
 * <b>흡수하지 않는다</b> — 결과를 모르는데 ack 를 주면 "확인 못 한 것이 성공" 으로 보고된다.
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
