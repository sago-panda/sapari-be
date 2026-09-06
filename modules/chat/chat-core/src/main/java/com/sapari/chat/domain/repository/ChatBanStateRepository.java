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
     * 강제하는 제약이 없다.
     *
     * <p><b>그중 가장 오래 가는 것을 돌려준다</b>(만료 없음이 가장 길다). 이건 편의가 아니라 계약이다 —
     * 호출자가 이 값의 만료를 미러에 쓰고, 미러가 집행을 한다. 아무거나 돌려주는 구현으로 바뀌면 짧은
     * 밴이 미러에 실려 정본보다 일찍 풀린다.
     */
    Optional<ChatBan> findActive(UUID userId, Instant now);

    /**
     * 밴을 남긴다.
     *
     * <p><b>호출자가 트랜잭션을 열어야 한다.</b> 구현이 {@code @Modifying} 네이티브 INSERT라 경계가 없으면
     * {@code "No active transaction for update or delete query"}로 실패한다(실측). 이 포트는 경계를
     * 만들지 않는다 — 강퇴 흐름은 이 커밋이 확정된 <i>다음에</i> Redis로 넘어가야 하므로 경계가 어디서
     * 닫히는지가 설계의 일부이고, 그 판단은 유스케이스의 몫이다.
     */
    void append(ChatBan ban);
}
