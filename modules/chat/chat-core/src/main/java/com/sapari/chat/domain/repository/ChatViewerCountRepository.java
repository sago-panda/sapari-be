package com.sapari.chat.domain.repository;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 방별 고유 시청자 수(블로킹). 세는 규칙은 {@link ChatSessionRepository#count} 와 같다 — 다른 도메인이
 * 블로킹 스택(live-app)에서 여러 방을 한 번에 읽기 위한 형제다.
 */
public interface ChatViewerCountRepository {

    /** 요청한 모든 방을 담아 돌려준다. 세션이 없는 방은 0. */
    Map<UUID, Long> countViewers(Collection<UUID> roomIds);
}
