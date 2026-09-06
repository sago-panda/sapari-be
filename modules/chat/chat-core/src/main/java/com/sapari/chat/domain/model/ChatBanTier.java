package com.sapari.chat.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 누적 강퇴 횟수가 만드는 밴 단계.
 *
 * <p>기간은 근사값이다 — 한 달을 30일, 한 해를 365일로 센다. 밴은 달력 경계가 아니라 "이만큼 못 들어온다"는
 * 길이라서 월말·윤년을 따질 이유가 없고, 따지기 시작하면 만료 시각이 시간대에 딸려간다.
 *
 * <p><b>영구는 여기 없다.</b> 자동 제재는 전부 스스로 만료된다 — 서버가 사람 손 없이 거는 것 중에
 * 되돌릴 수 없는 것을 두지 않는다. 이 저장소가 같은 판단을 반복해 왔다: 강퇴 명단은 삭제가 아니라 만료로
 * 회수하고, 오염된 키도 자가치유 DEL을 하지 않으며, 밴 만료는 상태 컬럼이 아니라 TTL이다. 되돌리는 코드가
 * 없는 상태에서 되돌릴 수 없는 제재를 자동으로 거는 것은 그 판단들과 어긋난다.
 *
 * <p>영구 밴 자체는 남아 있다 — {@code chat_ban.expires_at}이 NULL인 행이고, 그건 <b>사람이 넣는다</b>.
 * 그 경로(수동 밴·해제)는 admin-app 소관이고 아직 없다.
 */
public enum ChatBanTier {

    ONE_WEEK(3, Duration.ofDays(7)),
    ONE_MONTH(6, Duration.ofDays(30)),
    /** 자동 제재의 상한. 설계 문서의 12회 영구 밴은 <b>사람이 넣는 것</b>으로 옮겼다. */
    ONE_YEAR(9, Duration.ofDays(365));

    private final int threshold;
    private final Duration length;

    ChatBanTier(int threshold, Duration length) {
        this.threshold = threshold;
        this.length = length;
    }

    /**
     * 누적 강퇴 횟수에 해당하는 가장 높은 단계. 임계 미달이면 비어 있다.
     *
     * <p><b>정확히 일치가 아니라 이상으로 본다.</b> 설계 문서의 임계표는 3·6·9회를 정확값으로 적었다. 강퇴가 한 번에 하나씩 늘어나는 동안은 두 해석이 같은 답을 낸다 — 3이 되는 순간
     * 1주, 6이 되는 순간 1달이다. 갈리는 건 표가 다루지 않은 자리뿐이다.
     *
     * <p>정확값으로 읽으면 그 자리에서 아무 일도 일어나지 않는다. 2년 창에서 오래된 강퇴가 빠져 카운트가
     * 줄었다가 다시 오르거나, 다른 방에서 동시에 강퇴가 들어와 카운트가 임계를 건너뛰면, 그 사용자는
     * 다음 임계까지 아무 제재도 받지 않는다. 이상으로 읽으면 표가 정한 자리에서는 표와 같은 답을 내고,
     * 표가 침묵하는 자리에서만 안전한 쪽으로 기운다.
     */
    public static Optional<ChatBanTier> of(long kickCount) {
        ChatBanTier[] tiers = values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (kickCount >= tiers[i].threshold) {
                return Optional.of(tiers[i]);
            }
        }
        return Optional.empty();
    }

    /** 만료 시각. 자동 제재에는 영구가 없으므로 <b>항상 값이 있다</b>. */
    public Instant expiresAt(Instant now) {
        return now.plus(length);
    }
}
