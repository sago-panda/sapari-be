package com.sapari.chat.command;

import java.util.UUID;

/**
 * 사전 전체를 읽는 명령 (live-app REST, ADMIN 전용).
 *
 * <p><b>조회에도 명령이 있는 이유는 권한이다.</b> 이 응답은 "플랫폼이 무엇을 막는가" 의 전체
 * 목록이고, 그건 우회하려는 사람에게 가장 쓸모 있는 값이다 — 어떤 표기가 사전에 없는지를
 * 알려 준다. 그래서 읽기도 ADMIN 전용이고, 판정에 쓰는 주체가 인자로 들어온다.
 *
 * <p>읽기가 있어야 삭제가 쓸 수 있다는 것이 이 엔드포인트의 존재 이유다 — 목록을 못 보면
 * 운영자는 무엇을 뺄지 모른다.
 */
public record ListFilterWordsCommand(UUID actorId, String actorRole) {

    public ListFilterWordsCommand {
        if (actorId == null) {
            throw new IllegalArgumentException("actorId는 필수입니다.");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole은 필수입니다.");
        }
    }
}
