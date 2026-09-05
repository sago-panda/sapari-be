package com.sapari.chat.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sapari.chat.domain.model.ChatBan;

/**
 * 밴의 정본(Postgres). <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p>Redis의 {@code chat:banned:} 키는 이 테이블의 미러다({@link ChatBanWriteRepository}). 판정은 미러가
 * 하지만 근거는 여기 있고, 미러가 날아가도 여기서 다시 만들 수 있다.
 */
public interface ChatBanStateRepository {

    /**
     * 지금 유효한 밴. 만료가 없거나({@code expires_at IS NULL}) 아직 지나지 않은 행이다.
     *
     * <p>여러 행이 살아 있을 수 있다 — 자동 밴과 관리자 수동 밴이 겹칠 수 있고, 테이블에 사용자당 하나를
     * 강제하는 제약이 없다. 그중 하나만 돌려준다. 판정에는 "있는가"만 쓰이므로 어느 것이든 상관없지만,
     * 미러에 쓸 TTL은 달라질 수 있다.
     */
    Optional<ChatBan> findActive(UUID userId, Instant now);

    void append(ChatBan ban);
}
