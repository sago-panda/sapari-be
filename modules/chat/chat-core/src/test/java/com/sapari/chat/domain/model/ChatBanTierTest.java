package com.sapari.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 누적 강퇴가 만드는 제재의 <b>모양과 상한</b>을 고정한다.
 *
 * <p>여기서 가장 중요한 단언은 "몇 회에 무엇"이 아니라 <b>자동으로는 영구가 나오지 않는다</b>는 것이다.
 * 되돌리는 코드가 없는 상태에서 되돌릴 수 없는 제재를 서버가 사람 손 없이 걸지 않는다는 결정이고,
 * 이 저장소가 같은 판단을 반복해 왔다 — 강퇴 명단은 삭제가 아니라 만료로 회수하고, 오염된 키도
 * 자가치유 DEL을 하지 않는다. 영구 밴은 사람이 넣는 것으로 남는다.
 */
@DisplayName("ChatBanTier — 자동 제재는 스스로 만료된다")
class ChatBanTierTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @ParameterizedTest(name = "누적 {0}회 → {1}")
    @CsvSource({
            "0, ", "1, ", "2, ",
            "3, ONE_WEEK", "4, ONE_WEEK", "5, ONE_WEEK",
            "6, ONE_MONTH", "8, ONE_MONTH",
            "9, ONE_YEAR", "12, ONE_YEAR", "100, ONE_YEAR",
    })
    @DisplayName("임계는 '이상'으로 읽는다 — 표가 침묵하는 자리에서 제재가 통째로 빠지지 않게")
    void mapsCountToTier(long count, String expected) {
        // when
        var tier = ChatBanTier.of(count);

        // then
        if (expected == null) {
            assertThat(tier).isEmpty();
        } else {
            assertThat(tier).contains(ChatBanTier.valueOf(expected));
        }
    }

    @Test
    @DisplayName("⭐ 어떤 누적에도 자동 영구 밴은 나오지 않는다 — 푸는 코드가 없는 제재를 자동으로 걸지 않는다")
    void automaticEscalationNeverProducesAPermanentBan() {
        // given: 임계 최상단을 훌쩍 넘긴 값까지 훑는다
        for (long count = 0; count <= 1_000; count++) {
            // when
            Instant expiry = ChatBanTier.of(count).map(tier -> tier.expiresAt(NOW)).orElse(null);

            // then: 밴이 생겼다면 반드시 만료가 있고, 1년을 넘지 않는다
            if (expiry != null) {
                assertThat(expiry)
                        .as("누적 %d회에서 만료 없는 밴이 나왔다 — 되돌릴 수 없는 자동 제재다", count)
                        .isNotNull()
                        .isBeforeOrEqualTo(NOW.plus(Duration.ofDays(365)));
            }
        }
    }

    @Test
    @DisplayName("단계가 늘어날수록 기간도 늘어난다 — 순서가 뒤집히면 재범이 가벼워진다")
    void tiersIncreaseMonotonically() {
        // given
        var tiers = ChatBanTier.values();

        // when & then
        for (int i = 1; i < tiers.length; i++) {
            assertThat(tiers[i].expiresAt(NOW))
                    .as("%s가 %s보다 짧다", tiers[i], tiers[i - 1])
                    .isAfter(tiers[i - 1].expiresAt(NOW));
        }
    }

    @Test
    @DisplayName("모든 단계에 만료가 있다 — 목록에 영구가 다시 끼어들면 여기서 깨진다")
    void everyTierExpires() {
        assertThat(Arrays.stream(ChatBanTier.values()).map(tier -> tier.expiresAt(NOW)))
                .allSatisfy(expiry -> assertThat(expiry).isNotNull());
    }
}
