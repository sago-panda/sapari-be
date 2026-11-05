package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.chat.application.port.ProfanityDictionarySnapshot;

/**
 * 스냅샷 와이어 포맷 — <b>깨진 값을 깨진 것으로 알아보는가</b>를 잰다.
 *
 * <p>이 포맷이 지키는 것은 "읽는 쪽이 반쪽 사전으로 교체하지 않는다" 다. 전파 경로에서 스냅샷을
 * 쓰는 것은 live-app 이지만 정본을 손으로 고치는 경로가 있고(그것이 교체 지점에 게이트를 둔
 * 이유다) 그러면 항목에 줄바꿈이 섞이거나 개수가 어긋난 값이 올 수 있다. 조용히 절반만 읽으면
 * 그 방의 마스킹이 조용히 달라진다.
 */
@DisplayName("사전 스냅샷 포맷 — 깨진 값을 알아본다")
class ProfanityDictionarySnapshotCodecTest {

    private static final String LINE = "\n";

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    /** 발행 스크립트가 하는 일 — 본문 앞에 버전 한 줄을 붙인다. 테스트가 그 자리를 대신한다. */
    private static String withVersion(long version, String body) {
        return version + LINE + body;
    }

    @Test
    @DisplayName("⭐ 왕복 — 두 집합과 버전이 그대로 돌아온다")
    void roundTrips() {
        Set<String> words = ordered("시발", "개새끼", "지랄");
        Set<String> whitelist = ordered("시발점", "지랄탄");

        ProfanityDictionarySnapshot decoded = ProfanityDictionarySnapshotCodec.decode(
                withVersion(7, ProfanityDictionarySnapshotCodec.encodeBody(words, whitelist)));

        assertThat(decoded.version()).isEqualTo(7);
        assertThat(decoded.words()).containsExactlyElementsOf(words);
        assertThat(decoded.whitelist()).containsExactlyElementsOf(whitelist);
    }

    /**
     * 화이트리스트가 비는 것은 정상이다 — 번들에도 38건뿐이고 0 이어도 사전은 돈다.
     * 욕설이 비는 쪽은 여기서 거부하지 않는다: 그 판정은 사전 생성 문
     * ({@code ProfanityDictionary.of})이 하고, 이 코덱은 포맷만 본다. 둘을 한곳에 두면
     * "포맷이 깨졌다" 와 "내용이 위험하다" 가 같은 예외로 올라와 당직자가 가를 수 없다.
     */
    @Test
    @DisplayName("빈 화이트리스트도 왕복한다 — 0 은 정상 값이다")
    void anEmptyWhitelistRoundTrips() {
        ProfanityDictionarySnapshot decoded = ProfanityDictionarySnapshotCodec.decode(
                withVersion(1, ProfanityDictionarySnapshotCodec.encodeBody(ordered("시발"), Set.of())));

        assertThat(decoded.words()).containsExactly("시발");
        assertThat(decoded.whitelist()).isEmpty();
    }

    /**
     * ⭐ <b>개수를 머리에 둔 이유가 이 벡터다.</b>
     *
     * <p>섹션을 구분 토큰(예: {@code "--"})으로 갈랐다면 그 토큰이 정당한 사전 항목일 수 있고
     * (로더는 글자 없는 항목을 거부하지 않는다 — 규칙 (2)는 경고다) 그 순간 포맷이 조용히
     * 깨진다. 개수는 예약어가 없고, 줄 수가 선언과 어긋나면 그 자리에서 드러난다.
     */
    @Test
    @DisplayName("⭐ 선언한 수보다 줄이 모자라면 거부한다 — 항목에 줄바꿈이 섞인 경로")
    void aTruncatedPayloadIsRefused() {
        // 욕설 3개라고 선언하고 둘만 싣는다 — 정본을 손으로 고쳐 항목에 줄바꿈이 들어간 모양이
        // 이 쪽으로 떨어진다(앞 항목이 두 줄로 쪼개져 뒤가 밀린다).
        String broken = "1" + LINE + "3" + LINE + "0" + LINE + "시발" + LINE + "개새끼" + LINE;

        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("줄 수가 선언과 다르다")
                .as("무엇이 몇 개 필요한지 없으면 당직자가 어느 쪽이 틀렸는지 모른다")
                .hasMessageContaining("줄 6개가 필요한데");
    }

    /**
     * ⭐ 수는 맞는데 <b>내용이 빈</b> 경우 — 위 개수 검사가 못 잡는 나머지 절반이다.
     *
     * <p>끝 줄바꿈이 둘이면 {@code decode} 는 하나만 떼므로 빈 문자열 하나가 항목 자리에
     * 들어오고, 줄 수는 선언과 맞는다. 처음 구현이 정확히 거기서 통과했다 — 빈 문자열이
     * 세 번째 욕설로 트라이에 들어갔다. 개수 검사와 이 검사는 <b>둘 다</b> 있어야 한다.
     */
    @Test
    @DisplayName("⭐ 수는 맞아도 빈 항목이 있으면 거부한다 — 개수 검사가 못 잡는 절반")
    void aBlankEntryIsRefusedEvenWhenTheCountMatches() {
        // 욕설 2개 선언 · 두 번째가 빈 줄. 끝 빈 원소 하나는 decode 가 떼므로 줄 수는 맞는다.
        String broken = "1" + LINE + "2" + LINE + "0" + LINE + "시발" + LINE + LINE;

        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("욕설 항목이 비어 있다")
                .as("몇 번째 줄인지 없으면 1,000줄 스냅샷에서 찾을 수 없다")
                .hasMessageContaining("5번째 줄");
    }

    /**
     * ⭐ 선언보다 줄이 <b>많은</b> 경우 — 그리고 이것이 포맷이 막으려던 바로 그 모양이다.
     *
     * <p>항목 하나에 줄바꿈이 섞이면 줄 수가 <b>늘어난다</b>(개수는 그대로다). 짧아지는 쪽만
     * 보면 그 경로가 통과하고, 사전은 세 줄 중 둘만 받아 조용히 다른 사전이 된다.
     *
     * <p>이 벡터가 필요하다는 것을 되돌림이 알려 줬다 — {@code != declared} 를
     * {@code < declared} 로 되돌려도 다른 벡터는 전부 초록이었다(짧은 쪽은 둘 다 잡는다).
     * 그래서 엄격 등호를 지키는 것은 이 테스트 하나다.
     */
    @Test
    @DisplayName("⭐ 선언보다 줄이 많아도 거부한다 — 항목에 줄바꿈이 섞이면 늘어난다")
    void anOverlongPayloadIsRefused() {
        // 욕설 2개라고 선언하고 3줄을 싣는다 — '개새끼' 가 두 줄로 쪼개진 모양이다.
        String broken = "1" + LINE + "2" + LINE + "0" + LINE
                + "시발" + LINE + "개새" + LINE + "끼" + LINE;

        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("줄 수가 선언과 다르다")
                .hasMessageContaining("줄 5개가 필요한데 6개다");
    }

    @Test
    @DisplayName("머리가 없는 값은 거부한다")
    void aHeaderlessPayloadIsRefused() {
        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode("1" + LINE + "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("머리가 없다");
    }

    @Test
    @DisplayName("빈 값은 거부한다 — 발행 쪽이 빈 것을 썼다는 뜻이다")
    void anEmptyPayloadIsRefused() {
        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비었다");
    }

    /**
     * 실패 메시지에 <b>값을 싣지 않는다</b> — 신뢰경계 밖 문자열이라 개행 삽입으로 로그를
     * 위조할 수 있다. 길이는 남긴다: "빈 줄" 과 "숫자가 아닌 긴 값" 은 원인이 다르다.
     */
    @Test
    @DisplayName("⭐ 숫자가 아닌 머리는 거부하고, 그 값을 로그에 싣지 않는다")
    void aNonNumericHeaderIsRefusedWithoutEchoingTheValue() {
        String poison = "9" + LINE + "NOT_A_NUMBER" + LINE + "0" + LINE;

        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode(poison))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("욕설 수가 숫자가 아니다")
                .hasMessageContaining("길이 12")
                .as("값을 그대로 실으면 개행 삽입으로 로그를 위조할 수 있다")
                .hasMessageNotContaining("NOT_A_NUMBER");
    }

    /**
     * ⭐ <b>머리 문법이 발행 토큰과 같다 — 여기가 세 번째 문법이었다.</b>
     *
     * <p>{@code decode} 가 {@code Long.parseLong(value.strip())} 였다. 토큰 문법은
     * {@code 숫자 1~15 + (끝|개행)} 인데 이쪽은 공백·부호를 받는다 — 실측 일곱 벡터 중 넷이
     * 갈렸다: {@code " 12"}·{@code "-5"}·{@code "+7"}·{@code "\t3"} 를 토큰은 거부하고 이쪽은
     * 받았다. 정책 차이(0 이냐 던지냐)가 아니라 <b>수용 집합 차이</b>다.
     *
     * <p>그 차이의 대가: 버전 키 {@code "9"} + 손으로 고친 본문 머리 {@code "-5"} 면 리더가 9 를
     * 주고 이 자리가 <b>-5</b> 를 줘서 {@code processedVersion = -5} 가 적힌다. 그 뒤 분마다
     * {@code 9 != -5} 로 영구히 재빌드·재install 하고 "사전 버전이 뒤로 갔다" WARN 이 전 Pod 에서
     * 분당 한 줄씩 나간다. 엄격 문법을 둔 이유가 "손으로 고친 본문" 인데 <b>적용 직전 경로만</b>
     * 그 문법 밖에 있었다.
     *
     * <p>정책은 여전히 다르다 — 토큰은 "모른다 → 0"(다른 키로 떨어지고 발행이 덮어 고친다),
     * 이쪽은 <b>던진다</b>(적용 직전이라 0 으로 접으면 깨진 스냅샷을 조용히 집행한다).
     */
    @ParameterizedTest(name = "머리=\"{0}\"")
    @ValueSource(strings = {" 12", "-5", "+7", "\t3", "12abc", "1000000000000000"})
    @DisplayName("⭐ 토큰 문법을 벗어난 머리는 거부한다 — 수용 집합이 발행 쪽과 같다")
    void aHeadOutsideTheTokenGrammarIsRefused(String head) {
        String payload = head + LINE + "1" + LINE + "0" + LINE + "시발" + LINE;

        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode(payload))
                .as("이 값을 받으면 processedVersion 이 그것으로 적히고 전 Pod 이 분마다 헛돈다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("문법을 벗어난다")
                .as("값을 그대로 실으면 개행 삽입으로 로그를 위조할 수 있다")
                .hasMessageNotContaining(head.strip());
    }

    @Test
    @DisplayName("앞이 0 인 머리는 문법 안이다 — 숫자만 있고 뒤가 개행이다")
    void aZeroPaddedHeadIsInsideTheGrammar() {
        assertThat(ProfanityDictionarySnapshotCodec.decode(
                        "007" + LINE + "1" + LINE + "0" + LINE + "시발" + LINE).version())
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("음수 개수는 거부한다 — 파싱은 되지만 담을 수 없는 값이다")
    void aNegativeCountIsRefused() {
        assertThatThrownBy(() -> ProfanityDictionarySnapshotCodec.decode(
                        "1" + LINE + "-1" + LINE + "0" + LINE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("항목 수가 음수다");
    }

    /**
     * 끝의 빈 줄이 있어도 없어도 같게 읽는다. {@code encodeBody} 는 항목마다 줄바꿈을 붙이므로
     * 끝에 빈 줄이 하나 생기는데, 전송·셸 경유에서 그것이 떨어질 수 있다(실측: redis-cli 왕복에서
     * 명령 치환이 끝 줄바꿈을 잘라낸다). 둘 다 받아야 그 경로가 포맷 오류로 보이지 않는다.
     */
    @Test
    @DisplayName("끝 줄바꿈이 있어도 없어도 같게 읽는다")
    void aTrailingNewlineDoesNotChangeTheResult() {
        String body = ProfanityDictionarySnapshotCodec.encodeBody(ordered("시발"), ordered("시발점"));
        String withTrailing = withVersion(3, body);
        String withoutTrailing = withTrailing.substring(0, withTrailing.length() - 1);

        assertThat(ProfanityDictionarySnapshotCodec.decode(withoutTrailing))
                .isEqualTo(ProfanityDictionarySnapshotCodec.decode(withTrailing));
    }
}
