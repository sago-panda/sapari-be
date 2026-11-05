package com.sapari.chat.infrastructure.redis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import com.sapari.chat.application.port.ProfanityDictionarySnapshot;

/**
 * 스냅샷의 와이어 포맷 — <b>쓰는 쪽과 읽는 쪽의 단일 소스</b>다.
 *
 * <pre>
 *   0행: 버전(long)          ← 발행 스크립트가 붙인다(본문에는 없다)
 *   1행: 욕설 수(int)
 *   2행: 화이트리스트 수(int)
 *   3행~: 욕설 × 욕설 수, 이어서 화이트리스트 × 화이트리스트 수
 * </pre>
 *
 * <p><b>왜 줄 구분인가.</b> 사전 항목에는 공백·보이지 않는 글자가 들어올 수 없다 — 로더가
 * 적재 시점에 거부하고({@code ProfanityDictionary.reject}) 그 거부를 테스트가 못 박는다.
 * 줄바꿈은 {@code Character.isWhitespace} 가 참이므로 그 거부군에 들어 있다. 즉 구분자가
 * 항목 안에 나타날 수 없는 것이 <b>이미 성립하는 불변식</b>이고, 이스케이프가 필요 없다.
 * JSON 으로 싸면 그 불변식에 기대지 않아도 되지만 Jackson 과 이스케이프 규칙이 포맷의 일부가
 * 되고, 발행 스크립트가 버전을 머리에 붙이는 것도 할 수 없다.
 *
 * <p><b>왜 구분 토큰이 아니라 개수인가.</b> {@code "--"} 같은 토큰을 섹션 구분자로 두면 그것이
 * 정당한 사전 항목일 수 있고(로더는 글자 없는 항목을 거부하지 않는다 — 규칙 (2)는 경고다)
 * 그 순간 포맷이 조용히 깨진다. 개수는 예약어가 없고 <b>자기 검증</b>이다: 줄 수가 선언과
 * 맞지 않으면 그 자리에서 깨진 스냅샷으로 판정된다.
 *
 * <p>항목 순서를 보존한다({@code LinkedHashSet}). 집합의 의미에 순서는 없지만, 두 Pod 의
 * 적재 로그를 눈으로 비교하는 것이 사전 전파의 주된 진단 수단이다.
 */
final class ProfanityDictionarySnapshotCodec {

    private static final String LINE = "\n";

    /**
     * 머리 버전의 자리 수 상한 — <b>Lua 의 double 산술이 정확한 범위</b>다.
     *
     * <p>⚠️ 한때 18 이었고 근거가 두 군데 틀렸다. 실측(Redis 7.4.12):
     * <ul>
     *   <li>부정확이 시작되는 자리는 19자리가 아니라 <b>2<sup>53</sup> = 9007199254740992</b>
     *       (16자리)다 — {@code 9007199254740993} 을 넣으면 {@code …992} 가 써진다.
     *   <li>{@code 1e+NN} 이 <b>키에 써지기</b> 시작하는 자리는 또 다르다. 그것은 {@code tonumber}
     *       가 아니라 Lua 의 {@code ..} 가 {@code %.14g} 를 쓰기 때문이고, 값이 15자리가 되는
     *       순간이다({@code 100000000000000} → {@code 1e+14}). 자리올림을 감당하면 토큰 13자리가
     *       한계였다 — 선언값 18 과 다섯 자리 어긋나 있었다.
     * </ul>
     *
     * <p>포맷 쪽은 {@code string.format('%d', version)} 으로 닫았으므로({@code PUBLISH_SNAPSHOT})
     * 남는 경계는 <b>산술</b>뿐이다. 15자리면 {@code 999999999999999 + 1 = 10}<sup>15</sup> 이고
     * 2<sup>53</sup> 아래라 {@code +1} 과 비교가 정확하다. 발행 10<sup>15</sup>회는 도달하지 않고,
     * 이 상한이 막는 적은 유기적 증가가 아니라 <b>키에 손으로/덤프 복원으로 들어오는 큰 수</b>다.
     */
    private static final int MAX_VERSION_DIGITS = 15;

    private ProfanityDictionarySnapshotCodec() {
    }

    /**
     * 머리 버전의 <b>문법</b> — 이 자리가 그 문법의 단일 소스다.
     *
     * <p>문법: <b>숫자 1~18자로 시작하고, 그 뒤는 문자열의 끝이거나 개행이다.</b> 그 밖은 전부
     * "읽을 수 없다"({@link OptionalLong#empty()})다. 버전 키는 끝 쪽, 스냅샷 본문은 개행 쪽을
     * 쓰므로 한 규칙이 둘을 덮는다.
     *
     * <p><b>왜 한 곳이어야 하는가.</b> 같은 문자열을 Lua 스크립트와 자바가 각자 읽는데, 둘이
     * 다르면 조건부 발행이 <b>평시에도</b> 어긋나 재시도만 소진하고 던지는 메시지가 엉뚱한 곳
     * (버전 키)을 가리킨다. 실측으로 벌어졌던 갈림 — {@code "12abc\nbody"}(Lua 12 · 자바 0) ·
     * {@code " 12\nbody"}(Lua 0 · 자바 12) · {@code "-5"} · {@code "+7"} · {@code "\t3"} ·
     * 20자리(Lua {@code 1e+20} · 자바 0). 열 벡터 중 여섯이 갈렸다.
     *
     * <p><b>왜 접두만 떼지 않고 엄격한가.</b> 이 수를 쓰는 유일한 자리가 ABA 토큰이다. 손으로
     * 고친 본문의 앞 숫자를 토큰으로 받으면 그 본문이 토큰을 정하게 되므로, 깨진 값은 수로
     * 읽지 않고 "모른다" 로 떨어뜨린다 — 떨어지면 토큰이 다른 키로 가고 발행이 그 키를 덮어
     * 고친다. 쓰는 쪽은 언제나 {@code 숫자 + 개행 + 본문} 이므로 정상 값이 이 문법을 벗어날
     * 수 없다.
     *
     * <p>Lua 거울은 {@code ProfanityDictionarySnapshotRedisPublisher.PUBLISH_SNAPSHOT} 의
     * {@code token()} 이다. <b>한쪽을 고치면 다른 쪽도 고친다</b> — 둘이 같다는 것을 테스트가
     * 같은 벡터로 못 박는다.
     */
    static OptionalLong readVersionToken(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        int end = 0;
        while (end < raw.length() && raw.charAt(end) >= '0' && raw.charAt(end) <= '9') {
            end++;
        }
        if (end == 0 || end > MAX_VERSION_DIGITS) {
            return OptionalLong.empty();
        }
        if (end < raw.length() && raw.charAt(end) != '\n') {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(raw.substring(0, end)));
    }

    /**
     * 버전을 <b>뺀</b> 본문. 버전은 발행 스크립트가 머리에 붙인다.
     *
     * <p>⚠️ 한때 이 자리에 "스크립트가 {@code INCR} 한 값을 붙인다 — 클라이언트가 번호를 정하면
     * Pod 둘일 때 같은 번호를 두 번 쓴다" 고 적혀 있었다. <b>둘 다 지금은 거짓이다.</b>
     * {@code INCR} 은 ABA 를 닫으면서 빠졌고(스크립트가 {@code current + 1} 을 쓴다),
     * 번호를 집는 것은 이제 클라이언트다({@code expectedVersion} 을 넘긴다). 같은 번호를 두 번
     * 쓰지 못하게 지키는 것은 서버측 {@code INCR} 이 아니라 <b>CAS</b> 다 — 번호가 그대로일
     * 때만 쓰므로 두 번째 발행자는 {@code -1} 로 떨어지고 다시 읽는다.
     */
    static String encodeBody(Set<String> words, Set<String> whitelist) {
        StringBuilder out = new StringBuilder();
        out.append(words.size()).append(LINE).append(whitelist.size()).append(LINE);
        for (String word : words) {
            out.append(word).append(LINE);
        }
        for (String entry : whitelist) {
            out.append(entry).append(LINE);
        }
        return out.toString();
    }

    /**
     * 스냅샷 전체(버전 + 본문)를 되돌린다.
     *
     * @throws IllegalStateException 포맷이 깨졌으면. 호출자가 이것을 잡아 <b>교체를 포기</b>하고
     *         이전 사전을 계속 쓴다 — 깨진 스냅샷으로 교체하는 것보다 옛 사전으로 집행하는
     *         것이 낫고, 받는 쪽은 깨뜨린 주체를 고칠 수 없다
     */
    static ProfanityDictionarySnapshot decode(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("사전 스냅샷이 비었다 — 발행 쪽이 빈 값을 썼다.");
        }
        // -1 을 줘서 끝의 빈 줄도 원소로 받는다. 그래야 끝에 몇 줄이 붙었는지를 <볼 수> 있고,
        // 아래에서 '하나까지만 허용' 으로 처리할 수 있다.
        List<String> lines = new ArrayList<>(List.of(payload.split(LINE, -1)));
        // ⚠️ encodeBody 가 항목마다 줄바꿈을 붙이므로 정상 값에도 끝에 빈 원소가 하나 생긴다.
        // 그것을 <먼저 떼지 않으면> 개수 검사가 그 빈 원소로 채워져 통과한다 — 실측으로
        // "욕설 3개 선언 + 2개 적재" 가 통과하면서 빈 문자열이 세 번째 욕설로 들어왔다.
        // 그래서 하나만 떼고, 그 뒤로는 <정확히> 같은지를 본다(아래 != declared).
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.size() < 3) {
            throw new IllegalStateException(
                    "사전 스냅샷에 머리가 없다 — 줄 " + lines.size() + "개(버전·욕설 수·화이트리스트 수가 필요하다).");
        }
        long version = parseVersionHead(lines.get(0));
        int wordCount = (int) parse(lines.get(1), "욕설 수");
        int whitelistCount = (int) parse(lines.get(2), "화이트리스트 수");
        if (wordCount < 0 || whitelistCount < 0) {
            throw new IllegalStateException(
                    "사전 스냅샷의 항목 수가 음수다 — 욕설 " + wordCount + " · 화이트리스트 " + whitelistCount + ".");
        }
        // 선언된 수와 실제 줄 수가 <정확히> 같아야 한다. 이 검사가 포맷의 자기 검증이고,
        // 항목에 줄바꿈이 섞여 들어온 경우(정본을 손으로 고친 경로)가 여기로 떨어진다 —
        // 한 항목이 두 줄로 쪼개지면 뒤가 밀려 수가 어긋난다.
        int declared = 3 + wordCount + whitelistCount;
        if (lines.size() != declared) {
            throw new IllegalStateException(
                    "사전 스냅샷의 줄 수가 선언과 다르다 — 욕설 " + wordCount + " + 화이트리스트 "
                            + whitelistCount + " 를 담으려면 줄 " + declared + "개가 필요한데 "
                            + lines.size() + "개다. 항목에 줄바꿈이 섞였거나 값이 잘렸다.");
        }
        return new ProfanityDictionarySnapshot(version,
                collect(lines, 3, wordCount, "욕설"),
                collect(lines, 3 + wordCount, whitelistCount, "화이트리스트"));
    }

    /**
     * 빈 항목을 거부한다. 수가 맞는데 내용이 빈 경우가 남기 때문이다 — 끝 줄바꿈이 둘이면
     * 위에서 하나만 떼므로 빈 문자열 하나가 항목 자리에 들어온다. 사전 항목은 비어 있을 수
     * 없고(로더가 공백 항목을 거부한다) 빈 항목이 트라이에 들어가면 매칭이 어떻게 되는지가
     * 이 포맷이 답할 문제가 아니다.
     */
    private static Set<String> collect(List<String> lines, int from, int count, String what) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = from; i < from + count; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                // 줄 번호를 인덱스로 센다. 한때 out.size() 로 셌는데 out 이 Set 이라 빈 항목
                // 앞에 중복이 있으면 번호가 그만큼 밀렸다 — 진단 문구가 엉뚱한 줄을 가리킨다.
                throw new IllegalStateException(
                        "사전 스냅샷의 " + what + " 항목이 비어 있다 — " + (i + 1)
                                + "번째 줄. 항목은 비어 있을 수 없다(로더가 공백 항목을 거부한다).");
            }
            out.add(line);
        }
        return out;
    }

    /**
     * 머리 줄의 버전 — <b>토큰과 같은 문법</b>으로 읽고, 문법을 벗어나면 <b>던진다.</b>
     *
     * <p>⚠️ 한때 이 자리가 {@code Long.parseLong(value.strip())} 이었다. 그러면 문법이 셋이 된다 —
     * 실측으로 일곱 벡터 중 넷이 갈렸다({@code " 12"}·{@code "-5"}·{@code "+7"}·{@code "\t3"} 을
     * 토큰은 거부하고 이쪽은 받았다). 정책 차이가 아니라 <b>수용 집합 차이</b>다.
     *
     * <p>그 차이의 대가: 버전 키 {@code "9"} + 손으로 고친 본문 머리 {@code "-5"} 면 리더가 9 를
     * 주고 이 자리가 <b>-5</b> 를 줘서 {@code processedVersion = -5} 가 적힌다. 그 뒤 분마다
     * {@code 9 != -5} 로 영구히 재빌드·재install 하고 "사전 버전이 뒤로 갔다" WARN 이 전 Pod 에서
     * 분당 한 줄씩 나간다 — 발행이 한 번 덮기 전까지 안 풀린다. 엄격 문법을 둔 이유가 "손으로
     * 고친 본문" 인데 <b>적용 직전 경로만 그 문법 밖에</b> 있었다.
     *
     * <p>정책은 토큰 쪽과 다르다 — 거기서는 "모른다 → 0"(다른 키로 떨어지고 발행이 덮어 고친다)
     * 이고 여기서는 <b>던진다.</b> 이 자리는 그 스냅샷을 <b>적용하기 직전</b>이라, 0 으로 접으면
     * 깨진 스냅샷을 조용히 받아 집행한다. 호출자가 이 예외를 잡아 교체를 포기하는 것이 설계다.
     */
    private static long parseVersionHead(String value) {
        return readVersionToken(value).orElseThrow(() -> new IllegalStateException(
                "사전 스냅샷의 버전이 문법을 벗어난다 — 길이 " + value.length()
                        + ". 숫자 1~" + MAX_VERSION_DIGITS + "자여야 한다(발행 쪽 토큰과 같은 문법)."));
    }

    private static long parse(String value, String what) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            // 값을 싣지 않는다 — 신뢰경계 밖 문자열이라 개행 삽입으로 로그를 위조할 수 있다.
            // 길이는 남긴다: "빈 줄" 과 "숫자가 아닌 긴 값" 은 원인이 다르다.
            throw new IllegalStateException(
                    "사전 스냅샷의 " + what + "가 숫자가 아니다 — 길이 " + value.length() + ".", e);
        }
    }
}
