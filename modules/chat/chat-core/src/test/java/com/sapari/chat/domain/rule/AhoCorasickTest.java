package com.sapari.chat.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
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

    @Nested
    @DisplayName("사전 모양에 대한 방어")
    class DictionaryShape {

        @Test
        @DisplayName("한 패턴이 다른 패턴의 접두인 경우 — 짧은 쪽도 독립적으로 잡힌다")
        void prefixOverlappingPatterns() {
            // given
            AhoCorasick ac = new AhoCorasick(List.of("시발", "시발놈"));

            // when & then
            assertThat(ac.containsAny("시발놈")).isTrue();
            assertThat(ac.containsAny("시발")).isTrue();
        }

        @Test
        @DisplayName("접두가 반복될 때 fail 링크를 타고 되돌아가 찾는다 — root로 떨어지면 놓친다")
        void failLinkBacktracking() {
            // given: 짧은 패턴을 일부러 빼서 fail 링크로만 도달하게 만든다.
            // "b"나 "ab"가 사전에 있으면 어떤 입력이든 직계 goto로 걸려 이 경로가 검증되지 않는다.
            AhoCorasick ac = new AhoCorasick(List.of("aab"));

            // when & then: "aaab"는 [a][a]까지 간 뒤 세 번째 a에서 실패해 fail 링크([aa]→[a])를 타야만
            // 다시 [a][a][b]로 이어진다. child.fail=root로 고정하면 여기서 놓친다.
            assertThat(ac.containsAny("aaab")).isTrue();
            assertThat(ac.containsAny("aab")).isTrue();
            assertThat(ac.containsAny("ab")).isFalse();
        }

        @Test
        @DisplayName("사전에 null·빈 문자열이 섞여도 무시하고 나머지로 동작한다 — 사전은 DB에서 온다")
        void ignoresNullAndEmptyPatterns() {
            // given: 로더가 공백 행이나 NULL 컬럼을 그대로 넘길 수 있다.
            // 빈 패턴을 트라이에 넣으면 모든 위치에서 매칭되어 전 문장이 마스킹된다.
            List<String> dirty = new ArrayList<>();
            dirty.add("욕설");
            dirty.add(null);
            dirty.add("");

            // when
            AhoCorasick ac = new AhoCorasick(dirty);

            // then
            assertThat(ac.containsAny("욕설이다")).isTrue();
            assertThat(ac.containsAny("멀쩡한 문장")).isFalse();
        }

        @Test
        @DisplayName("빈 사전은 아무것도 잡지 않는다 — 필터가 꺼진 상태여도 크래시하지 않는다")
        void emptyDictionaryMatchesNothing() {
            // given & when
            AhoCorasick ac = new AhoCorasick(List.of());

            // then
            assertThat(ac.containsAny("무슨 말이든")).isFalse();
            assertThat(ac.findAll("무슨 말이든")).isEmpty();
        }
    }
}
