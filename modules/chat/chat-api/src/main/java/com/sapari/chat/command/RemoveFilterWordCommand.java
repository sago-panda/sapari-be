package com.sapari.chat.command;

import java.util.UUID;

/**
 * 사전에서 항목을 빼는 명령 (live-app REST, ADMIN 전용).
 *
 * <p>추가와 같은 모양이다 — {@code kind}·{@code actorRole} 이 문자열인 이유도 같다
 * ({@link AddFilterWordCommand}).
 *
 * <p><b>이 경로가 막히면 안 되는 이유가 있다.</b> 사전 항목 하나가 오탐을 내면(정상어가 걸리면)
 * 그것을 멈추는 길이 이 명령뿐이다 — 전에는 그 항목을 빼는 배포밖에 없었고, {@code sex}·
 * {@code siva} 에서 이미 두 번 그 길로 갔다.
 */
public record RemoveFilterWordCommand(
        String kind,
        String word,
        UUID actorId,
        String actorRole
) {

    public RemoveFilterWordCommand {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind는 필수입니다.");
        }
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
