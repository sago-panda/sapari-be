package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.sapari.chat.application.port.ProfanityFilterFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 적재 경고의 <b>꼬리 안내가 입구마다 맞는지</b>를 잰다.
 *
 * <p>이 클래스에 테스트가 <b>0건</b>이었다. 그 사이에 {@code recoveryFor} 의 분기가 여럿
 * 들어왔고(수를 적지 않는 이유는 {@code ProfanityFilterFactory} 의 같은 규칙이다), 분기 키가 포트 상수가
 * 아니라 <b>리터럴 복제</b>였다 — 값이 바뀌면 조용히 일반 분기로 떨어지고 그때 틀리는 것이 바로
 * 운영자 복구 안내다. 상수로 바꾸고 그 표를 여기서 못 박는다.
 *
 * <p>왜 안내가 입구마다 다른가: {@code ADMIN 조회} 는 <b>정본 테이블</b>만 돌려준다. 전파돼 온
 * 스냅샷의 경고는 그 조회에 없고, 운영자가 방금 보낸 항목은 <b>커밋 전</b>이라 역시 없다.
 */
@DisplayName("사전 문 어댑터 — 꼬리 안내가 입구마다 맞는가")
class DictionaryProfanityFilterFactoryTest {

    private final ProfanityFilterFactory factory = new DictionaryProfanityFilterFactory();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(DictionaryProfanityFilterFactory.class);
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** 상한을 넘기는 경고를 만든다 — 꼬리 안내는 상한 초과에서만 나간다. */
    private String warnFor(String origin) {
        Set<String> whitelist = new LinkedHashSet<>();
        for (int i = 0; i < ProfanityFilterFactory.LOG_ENTRY_LIMIT + 3; i++) {
            whitelist.add("정상어" + i);
        }
        factory.from(origin, Set.of("시발"), whitelist);

        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("경고가 안 나갔다 — 벡터가 상한을 안 넘겼다"));
    }

    @Test
    @DisplayName("⭐ 정본에서 온 두 입구는 ADMIN 조회를 가리킨다 — 그 집합이 조회에 있다")
    void theSourceAndProbeOriginsPointAtTheAdminQuery() {
        for (String origin : List.of(
                ProfanityFilterFactory.ORIGIN_SOURCE, ProfanityFilterFactory.ORIGIN_PROBE)) {
            appender.list.clear();
            assertThat(warnFor(origin))
                    .as("이 입구의 집합은 findWords 그대로다: %s", origin)
                    .contains("ADMIN 조회 /api/v1/chat/filter-words")
                    .doesNotContain("못 본다");
        }
    }

    /**
     * ⭐ <b>admin 입구는 조회 포인터와 단서를 같이 준다.</b>
     *
     * <p>한때 이 분기가 "보낸 값을 확인" 만 말했다. 잘리는 꼬리가 보통 요청 항목이 아니다 —
     * {@code warningEntries} 가 {@code words} 루프를 먼저, {@code whitelist} 루프를 나중에 돌므로
     * {@code kind=PROFANITY} 추가에서 요청 항목의 경고는 머리 블록에 들고 꼬리는 화이트리스트
     * 경고로 채워진다. 그 행들은 조회로 전부 받을 수 있다. {@code kind=WHITELIST} 추가에서만
     * 요청 항목이 꼬리다 — <b>두 kind 중 하나에서만 참</b>인 문구였다.
     */
    @Test
    @DisplayName("⭐ admin 입구는 조회 포인터를 남기고 '보낸 항목은 조회에 없다' 를 단서로 붙인다")
    void theAdminOriginKeepsTheQueryPointerWithACaveat() {
        assertThat(warnFor(ProfanityFilterFactory.ORIGIN_ADMIN))
                .contains("ADMIN 조회 /api/v1/chat/filter-words")
                .as("요청 항목은 커밋 전이라 조회에 없다 — 그 단서가 없으면 빈손을 오해한다")
                .contains("커밋 전이라 조회에 없다");
    }

    @Test
    @DisplayName("⭐ 스냅샷 입구는 조회로 못 본다고 말한다 — 그 집합은 Redis 에서 왔다")
    void theSnapshotOriginSaysTheQueryCannotSeeIt() {
        assertThat(warnFor(ProfanityFilterFactory.ORIGIN_SNAPSHOT))
                .as("조회는 정본 테이블만 돌려준다 — 여기서 조회를 가리키면 당직자가 빈손이다")
                .contains("ADMIN 조회로 못 본다")
                .contains(ProfanityFilterFactory.ORIGIN_SNAPSHOT);
    }

    /**
     * ⭐ <b>로그에 나가는 {@code origin} 이 줄을 쪼개지 않는다 — 분기 <i>전부</i>에서.</b>
     *
     * <p>{@code summarize} 는 경고 수가 상한을 넘을 때만 꼬리를 붙이므로, 꼬리 안내 안에서만
     * 다듬으면 상한 미달 칸에서 생 {@code origin} 이 로그 인자로 나간다. 그래서 벡터가 둘이다 —
     * 상한을 <b>안</b> 넘는 칸과 넘는 칸.
     *
     * <p>이 줄은 {@code origin} 의 소비자 중 하나일 뿐이다. 거부 예외 메시지 축은
     * {@link #theOriginIsSanitizedBeforeTheDoorRuns} 가 잰다.
     */
    @Test
    @DisplayName("⭐ origin 이 줄을 쪼개지 않는다 — 상한 미달·초과 둘 다")
    void theLoggedOriginNeverBreaksTheLine() {
        String hostile = "chat:dict\n사전을 정본 것으로 갈아 끼웠다 — version=999";

        // (1) 상한 미달 — 경고 하나. 꼬리가 없어 꼬리 안내 안의 다듬기는 안 걸리는 칸이다.
        factory.from(hostile, Set.of("시발"), Set.of("정상어"));
        String few = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow();
        assertThat(few)
                .as("개행이 그대로 나가면 한 줄이 둘로 쪼개져 적용 성공 줄이 위조된다")
                .doesNotContain("\n")
                .contains("\\u000A");

        // (2) 상한 초과 — 꼬리까지 붙는 칸.
        appender.list.clear();
        assertThat(warnFor(hostile)).doesNotContain("\n").contains("\\u000A");
    }

    /**
     * ⭐ <b>{@code origin} 은 {@code of()} <i>앞에서</i> 다듬인다 — 거부 예외도 같은 값을 받는다.</b>
     *
     * <p>다듬기가 {@code log.warn} 앞에만 있으면 {@code of()} 가 <b>생 {@code origin}</b> 을
     * 받고, 문이 거부할 때 그 값이 예외 메시지에 박힌다 — 그 메시지는
     * {@code ManageFilterWordsService} 의 {@code log.error("… 사유={}")} 와
     * {@code ProfanityDictionaryRefreshHandler} 로 나간다. 로그 인자만 닫으면 닫은 집합이 안
     * 닫은 집합의 진부분집합이 된다.
     *
     * <p>두 축을 같이 잰다. 개행은 줄을 쪼개고, <b>길이</b>는 줄을 늘린다 — 실측으로 다듬기
     * 전에는 5,000자 {@code origin} 하나가 예외 메시지를 5,000자 넘게 만들었다. 접기만 걸면
     * 길이 축이 열린 채 남는다.
     */
    @Test
    @DisplayName("⭐ origin 은 of() 앞에서 다듬인다 — 거부 예외 메시지도 접히고 잘린다")
    void theOriginIsSanitizedBeforeTheDoorRuns() {
        String hostile = "chat:dict\n사전을 정본 것으로 갈아 끼웠다 — version=999";

        assertThatThrownBy(() -> factory.from(hostile, Set.of("시\u200B발"), Set.of()))
                .as("문이 거부하는 벡터여야 한다 — 아니면 이 테스트가 다른 것을 잰다")
                .isInstanceOf(IllegalStateException.class)
                .as("개행이 그대로 박히면 호출자의 log.error 한 줄이 둘로 쪼개져 적용 성공 줄을 "
                        + "위조할 수 있다")
                .hasMessageNotContaining("\n")
                .hasMessageContaining("\\u000A");

        assertThatThrownBy(() -> factory.from("x".repeat(5_000), Set.of("시\u200B발"), Set.of()))
                .as("길이 축은 접기가 안 막는다 — 다듬기가 of() 앞이어야 예외 메시지도 상한을 받는다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("…(5000자)")
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .hasSizeLessThan(1_000);
    }

    /**
     * ⭐ 모르는 입구는 <b>일반 분기</b>로 간다 — 그리고 그것이 안전한 쪽이다.
     *
     * <p>분기 키를 리터럴로 복제했을 때의 결함이 "상수가 바뀌면 조용히 일반 분기" 였는데, 일반
     * 분기 자신은 <b>틀린 안내를 하지 않는다</b>("그 출처를 직접 확인"). 위험은 정본에서 온
     * 입구가 그쪽으로 떨어져 <b>쓸 수 있는 조회 포인터를 잃는</b> 것이다. 그래서 분기를 재는
     * 위 테스트들이 전부 상수를 참조한다.
     */
    @Test
    @DisplayName("모르는 입구는 일반 분기로 간다 — 틀린 안내를 하지는 않는다")
    void anUnknownOriginFallsBackWithoutLying() {
        assertThat(warnFor("chat:somewhere-else"))
                .contains("ADMIN 조회로 못 본다")
                .doesNotContain("/api/v1/chat/filter-words 로 받아 다시 센다");
    }
}
