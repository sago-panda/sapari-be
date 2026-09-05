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
     * 밴을 미러에 반영한다. <b>만료는 늘어나기만 한다.</b>
     *
     * <p>이미 걸린 밴보다 짧은 만료는 <b>반영되지 않는다</b>(무동작). 만료 없는 영구 밴은 어떤 기한부
     * 밴으로도 덮이지 않는다. 같은 값을 다시 쓰는 것은 안전하다.
     *
     * <p><b>이건 구현 편의가 아니라 계약이다.</b> 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출자가
     * 각각 밴을 만들어 각자 여기에 쓴다. 덮어쓰는 구현이면 나중에 도착한 쪽이 이겨 짧은 TTL이 남고, 집행은
     * 이 미러가 하므로 그 사람은 정본에 한 달이 남아 있어도 일주일 뒤에 돌아온다. 호출자는 <b>이 성질에
     * 기대어</b> 자기가 만든 밴을 그대로 넘긴다 — 가장 긴 것을 골라 넘기지 않는다.
     *
     * <p>⚠️ <b>그래서 이 포트로는 밴을 줄이거나 풀 수 없다.</b> 관리자 감형·해제를 만들 때는 키 삭제가
     * 함께 와야 하고, 그걸 <b>같은 변경에</b> 넣어야 한다 — 이 계약만 읽고 짜면 짧은 만료를 넘기는 코드가
     * 조용히 무동작이 되고 그 이유가 어디에도 남지 않는다.
     *
     * @param expiresAt 만료 시각. {@code null}이면 영구라 TTL을 붙이지 않는다
     */
    void ban(UUID userId, Instant expiresAt, Instant now);
}
