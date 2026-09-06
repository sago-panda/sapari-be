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
     * <p>사용자당 행은 하나다 — 테이블에 유니크 제약이 있고, 갱신은 {@link #extendOrCreate}가 한다.
     *
     * <p><b>그럼에도 "가장 오래 가는 것"이 계약이다.</b> 호출자가 이 값의 만료를 미러에 쓰고 미러가
     * 집행을 하므로, 제약이 느슨해져 여러 행이 되살아나는 날에도 짧은 쪽이 실리면 안 된다.
     */
    Optional<ChatBan> findActive(UUID userId, Instant now);

    /**
     * 밴을 <b>늘리는 방향으로만</b> 남긴다 — 없으면 만들고, 있으면 더 긴 만료일 때만 늘린다.
     *
     * <p>덮어쓰기가 아닌 이유는 동시 강퇴 때문이다. 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두
     * 호출이 각각 "활성 밴 없음"을 읽고 각각 쓰는데(READ COMMITTED라 서로의 미커밋 행이 안 보인다),
     * 나중에 도착한 쪽이 짧으면 그 사람은 정본에 한 달이 남아 있어도 일주일 뒤에 돌아온다.
     * 미러({@code chat:banned:})가 같은 이유로 이미 단조라, 정본이 다른 규칙을 쓰면 둘이 갈린다.
     *
     * <p><b>돌려주는 것은 이 호출이 건 밴이 아니라 지금 정본에 남아 있는 밴이다.</b> 이미 더 긴 밴이
     * 있으면 이 호출은 아무것도 바꾸지 않고 그 긴 밴이 돌아온다 — 실패가 아니다. 이 구분이 없으면
     * 호출자가 정본에 없는 만료를 "새로 걸었다"고 기록하고 그 값을 미러에 싣는다.
     *
     * <p>⚠️ <b>이 포트로는 밴을 짧게 줄일 수 없다.</b> 관리자 감형은 행 삭제 후 재삽입이어야 하고,
     * <b>미러 삭제가 같은 변경에 함께 와야 한다</b> — 집행은 미러가 한다. 순서는 강퇴 경로와 같게
     * 정본 → 미러다. 중간에 끊기면 "정본은 짧아졌는데 미러가 길다"(과다 차단)로 기울고, 그 반대
     * (정본은 긴데 미러가 풀림)보다 안전하다.
     *
     * <p><b>호출자가 트랜잭션을 열어야 한다.</b> 구현이 {@code @Modifying} 네이티브 INSERT라 경계가 없으면
     * {@code "No active transaction for update or delete query"}로 실패한다(실측). 이 포트는 경계를
     * 만들지 않는다 — 강퇴 흐름은 이 커밋이 확정된 <i>다음에</i> Redis로 넘어가야 하므로 경계가 어디서
     * 닫히는지가 설계의 일부이고, 그 판단은 유스케이스의 몫이다.
     */
    ChatBan extendOrCreate(ChatBan ban);
}
