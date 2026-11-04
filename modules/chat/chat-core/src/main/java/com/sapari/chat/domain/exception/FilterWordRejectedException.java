package com.sapari.chat.domain.exception;

/**
 * 운영자가 보낸 사전 항목이 <b>사전 문</b>을 통과하지 못했다.
 *
 * <p>세 가지가 여기로 온다 — 보이지 않는 글자(그 항목은 평범한 입력을 영원히 못 잡는다),
 * K 예산 초과(가장 긴 항목 하나가 2B 윈도우 폭을 정하고 그 비용을 모든 메시지가 치른다),
 * 그리고 욕설 그 자체인 화이트리스트 항목(그 욕설이 사전 전체에서 풀린다).
 *
 * <p><b>이 예외가 가리키는 것은 운영자의 입력이다.</b> 정본에 이미 있던 항목이 원인일 때는
 * {@link FilterWordSourceInvalidException} 이 나간다 — 서비스가 <b>변경 전</b> 집합을 먼저
 * 검사해 그 둘을 가른다. 하나로 뭉개면 멀쩡한 단어를 보낸 운영자에게 "당신 단어가 틀렸다" 는
 * 거짓 안내가 간다.
 *
 * <p><b>사유를 응답에 싣지 않는다.</b> {@code ErrorResponse} 는 에러 코드의 고정 문구와
 * {@code requestId} 만 나르고, 사유는 {@code debugMessage} 로 로그에 간다 — 이 저장소의
 * 응답 계약이고, 그것을 넓히는 변경은 모든 도메인의 응답 모양을 바꾼다. 받는 쪽이 운영자라
 * 로그 접근이 있다는 전제가 선다.
 */
public class FilterWordRejectedException extends ChatException {

    public FilterWordRejectedException(String debugMessage, Throwable cause) {
        super(ChatErrorCode.FILTER_WORD_REJECTED, debugMessage, cause);
    }
}
