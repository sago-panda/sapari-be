package com.sapari.chat.command;

import java.util.UUID;

/**
 * 사전에 항목을 더하는 명령 (live-app REST, ADMIN 전용).
 *
 * <p><b>{@code kind} 를 문자열로 받는다.</b> 도메인 enum({@code FilterWordKind})은 chat-core 에
 * 있고 이 모듈은 거기에 의존하지 않는다. 같은 이유로 {@code actorRole} 도 문자열이고, 모르는
 * 이름의 판정은 core 가 쥔다 — 그 목록이 두 곳에 생기지 않게 한다.
 *
 * <p><b>{@code actorId}·{@code actorRole} 은 인증 주체에서 온다.</b> 요청 본문에 역할을 실을
 * 자리를 만들면 구매자가 자기를 ADMIN 이라 적고 사전을 고칠 수 있다 — 사전은 전 방 공용이라
 * 그 한 줄이 플랫폼 전체의 마스킹을 바꾼다.
 *
 * <p>사유를 받지 않는다. 받으면 자유 텍스트가 감사의 유일한 내용이 되는데 그건 누른 사람이
 * 지어낼 수 있는 값이다 — 밴 해제와 같은 판단이고, 남기는 것은 <b>누가 언제</b>다
 * ({@code chat_filter_word.created_by} · {@code created_at}).
 *
 * @param kind      {@code PROFANITY} 또는 {@code WHITELIST}
 * @param word      더할 항목. 모양·예산 검사는 core 의 사전 문이 한다
 * @param actorRole 인증 주체의 역할(플랫폼 역할 이름). <b>요청 본문에서 받지 말 것.</b>
 */
public record AddFilterWordCommand(
        String kind,
        String word,
        UUID actorId,
        String actorRole
) {

    public AddFilterWordCommand {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind는 필수입니다.");
        }
        // 공백만인 항목을 여기서 끊는다. 아래 사전 문도 거부하지만, 그쪽 메시지는 "보이지 않는
        // 글자" 를 말하므로 빈 값에 대해서는 원인을 잘못 가리킨다.
        if (word == null || word.isBlank()) {
            throw new IllegalArgumentException("word는 필수입니다.");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole은 필수입니다.");
        }
    }
}
