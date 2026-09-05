package com.sapari.chat.domain.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 밴 미러 쓰기({@code chat:banned:{userId}}). <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p>읽는 쪽은 {@code ChatBanRepository}(리액티브, streaming-app)이고 키의 <b>존재</b>만 본다. 그래서
 * 여기서도 값에 의미를 담지 않는다 — 만료는 TTL이 표현하고, 영구 밴은 TTL을 붙이지 않는다.
 *
 * <p>실패는 삼키지 않고 던진다. 미러에 못 쓴 밴은 아무도 막지 못하는 밴이라, 호출자가 실패로 처리하고
 * 재시도할 수 있어야 한다.
 */
public interface ChatBanWriteRepository {

    /**
     * 밴을 미러에 반영한다. 이미 있으면 새 만료로 덮어쓴다 — 같은 값을 다시 쓰는 것도 안전하다.
     *
     * @param expiresAt 만료 시각. {@code null}이면 영구라 TTL을 붙이지 않는다
     */
    void ban(UUID userId, Instant expiresAt, Instant now);
}
