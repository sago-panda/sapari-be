package com.sapari.liveapp.controller.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 사전 항목 추가 요청 본문 — <b>담기는 것이 둘뿐인 게 요점이다.</b>
 *
 * <p>누가 고치는지는 여기 없다. 인증된 주체에서 서버가 채운다 — 이 record 에 역할 필드를 하나
 * 두는 순간 구매자가 자기를 관리자라 적고 사전을 고칠 수 있고, 사전은 전 방 공용이라 그 한 줄이
 * 플랫폼 전체의 마스킹을 바꾼다({@code KickUserRequest} 와 같은 판단이다).
 *
 * <p>사유도 받지 않는다. 받으면 자유 텍스트가 감사의 유일한 내용이 되는데 그건 누른 사람이
 * 지어낼 수 있는 값이다 — 남기는 것은 <b>누가 언제</b>이고 서버가 채운다
 * ({@code chat_filter_word.created_by} · {@code created_at}).
 */
public record AddFilterWordRequest(
        @NotBlank(message = "kind는 필수입니다.")
        @Size(max = MAX_KIND_LENGTH, message = "kind가 너무 깁니다.")
        String kind,

        @NotBlank(message = "word는 필수입니다.")
        @Size(max = MAX_WORD_LENGTH, message = "word가 너무 깁니다.")
        String word
) {

    /**
     * {@code kind} 길이 상한 — 크기 방어다. 어느 이름이 유효한지는 core 가 판정하고
     * ({@code FilterWordKind}) 여기서는 메가바이트짜리 문자열이 그 아래까지 흘러가지 않게만 막는다.
     * 두 이름 중 긴 쪽이 {@code WHITELIST}(9자)라 넉넉히 잡아도 남용은 막힌다.
     */
    private static final int MAX_KIND_LENGTH = 32;

    /**
     * {@code word} 길이 상한 — 역시 크기 방어이고 <b>K 예산(16)이 아니다.</b>
     *
     * <p>예산을 여기 적지 않는 이유: 그 수는 지연 예산이고 한 곳에서만 정해져야 한다
     * ({@code ProfanityDictionary.MAX_WORD_LENGTH}). 여기에 복제하면 그 수를 바꾸는 커밋이
     * 고칠 자리가 하나 늘고, 웹 계층이 못 고쳐 조용히 갈린다 — 그러면 예산 안인 항목이 입구에서
     * 떨어지거나 그 반대가 된다. 거부는 core 의 문이 하고 그 사유가 응답에 실린다.
     *
     * <p>정본 열이 {@code varchar(64)} 라 그보다 넉넉한 값을 둔다 — 열 상한을 넘는 값은 어차피
     * core 의 문에서 떨어진다.
     */
    private static final int MAX_WORD_LENGTH = 128;
}
