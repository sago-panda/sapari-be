package com.sapari.chat.domain.model;

/**
 * 사전 항목의 종류 — 정본 {@code live_schema.chat_filter_word.kind} 의 두 값.
 *
 * <p>두 집합을 <b>한 테이블</b>에 담는 것은 정본 DDL 의 결정이고({@code CHECK (kind IN
 * ('PROFANITY', 'WHITELIST'))}), 그 이유는 둘이 함께 평가돼야 한다는 것이 사전의 성질이기
 * 때문이다 — 화이트리스트 항목은 자기가 어떤 욕설을 덮는지로만 의미가 있다. 테이블을 둘로
 * 가르면 그 쌍을 읽는 코드가 두 번 질의하고, 그 사이에 한쪽만 바뀐 상태를 볼 수 있다.
 *
 * <p>이름을 {@code enum} 으로 두는 이유: 열이 {@code varchar} 이고 {@code CHECK} 가 두 값만
 * 받으므로, 문자열로 다루면 오타가 DB 제약 위반(500)으로만 드러난다. 여기서 끊으면 컴파일에서
 * 끊긴다.
 */
public enum FilterWordKind {

    /** 마스킹 대상. */
    PROFANITY,

    /**
     * 마스킹에서 <b>덮어 빼는</b> 정상어. 욕설을 품은 더 긴 낱말이라야 의미가 있다 —
     * {@code 시발} 이 아니라 {@code 시발점}.
     */
    WHITELIST
}
