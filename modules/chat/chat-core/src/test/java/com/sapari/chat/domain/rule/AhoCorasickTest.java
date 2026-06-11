package com.sapari.chat.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AhoCorasick")
class AhoCorasickTest {

    @Nested
    @DisplayName("findAll — 실패 링크로 겹치는 패턴까지 모두 탐지")
    class FindAll {

        @Test
        @DisplayName("고전 he/she/his/hers — 접미 패턴(dictionary suffix link)까지 탐지")
        void overlappingSuffix() {
            AhoCorasick ac = new AhoCorasick(Set.of("he", "she", "his", "hers"));

            // "ushers": she[1,4), he[2,4), hers[2,6)
            assertThat(ac.findAll("ushers"))
                    .extracting(s -> s[0], s -> s[1])
                    .containsExactlyInAnyOrder(tuple(1, 4), tuple(2, 4), tuple(2, 6));
        }

        @Test
        @DisplayName("한글 겹침 — 시발/발새/새끼가 한 스캔에 모두 잡힌다")
        void overlappingKorean() {
            AhoCorasick ac = new AhoCorasick(Set.of("시발", "발새", "새끼"));

            // "시발새끼": 실패 링크가 없으면 발새/새끼를 놓친다
            assertThat(ac.findAll("시발새끼"))
                    .extracting(s -> s[0], s -> s[1])
                    .containsExactlyInAnyOrder(tuple(0, 2), tuple(1, 3), tuple(2, 4));
        }

        @Test
        @DisplayName("매치 없음 → 빈 결과")
        void noMatch() {
            AhoCorasick ac = new AhoCorasick(Set.of("시발", "개새끼"));

            assertThat(ac.findAll("안녕하세요")).isEmpty();
        }

        @Test
        @DisplayName("같은 패턴 여러 출현 → 각각 반환")
        void repeated() {
            AhoCorasick ac = new AhoCorasick(Set.of("ab"));

            assertThat(ac.findAll("abxab"))
                    .extracting(s -> s[0], s -> s[1])
                    .containsExactlyInAnyOrder(tuple(0, 2), tuple(3, 5));
        }
    }

    @Nested
    @DisplayName("containsAny")
    class ContainsAny {

        @Test
        @DisplayName("패턴이 중간에 포함되면 true (실패 링크 경유)")
        void containsInMiddle() {
            AhoCorasick ac = new AhoCorasick(Set.of("시발"));

            assertThat(ac.containsAny("구매시발송")).isTrue();
        }

        @Test
        @DisplayName("포함 안 되면 false")
        void notContains() {
            AhoCorasick ac = new AhoCorasick(Set.of("시발"));

            assertThat(ac.containsAny("구매시 발송")).isFalse();
        }

        @Test
        @DisplayName("빈 패턴 집합 → 항상 false")
        void emptyPatterns() {
            AhoCorasick ac = new AhoCorasick(Set.of());

            assertThat(ac.containsAny("아무거나")).isFalse();
        }
    }
}
