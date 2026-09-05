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
 * <p>영구 밴은 만료가 <b>없다</b>. 테이블의 {@code expires_at}이 NULL인 것과 같은 뜻이고, Redis 미러에도
 * TTL을 붙이지 않는다.
 */
public enum ChatBanTier {

    ONE_WEEK(3, Duration.ofDays(7)),
    ONE_MONTH(6, Duration.ofDays(30)),
    ONE_YEAR(9, Duration.ofDays(365)),
    PERMANENT(12, null);

    private final int threshold;
    private final Duration length;

    ChatBanTier(int threshold, Duration length) {
        this.threshold = threshold;
        this.length = length;
    }

    /**
     * 누적 강퇴 횟수에 해당하는 가장 높은 단계. 임계 미달이면 비어 있다.
     *
     * <p><b>정확히 일치가 아니라 이상으로 본다.</b> 설계 문서의 임계표는 3·6·9회를 정확값으로, 12회만
     * {@code +}로 적었다. 강퇴가 한 번에 하나씩 늘어나는 동안은 두 해석이 같은 답을 낸다 — 3이 되는 순간
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

    /** 만료 시각. 영구 밴은 {@code null}이다 — 테이블과 Redis 양쪽에서 "만료 없음"을 그렇게 표현한다. */
    public Instant expiresAt(Instant now) {
        return length == null ? null : now.plus(length);
    }
}
