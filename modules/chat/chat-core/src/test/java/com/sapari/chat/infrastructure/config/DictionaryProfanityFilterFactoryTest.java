package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>이 클래스에 테스트가 <b>0건</b>이었다. 그 사이에 분기 셋이 들어왔고, 분기 키가 포트 상수가
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
     * ⭐ <b>로그에 나가는 {@code origin} 이 줄을 쪼개지 않는다 — 네 분기 <i>전부</i>에서.</b>
     *
     * <p>⚠️ 한때 접기가 {@code recoveryFor} 의 <b>기본 분기 안</b>에만 걸려 있었다. 그 반환은
     * {@code summarize} 가 경고 수가 상한을 넘을 때만 꼬리에 붙이므로, 접힌 {@code origin} 은
     * "상한 초과 + 기본 분기" 에서만 나오고 <b>생 {@code origin} 은 네 분기 전부에서 언제나</b>
     * 로그 인자로 나갔다 — <b>닫은 집합이 안 닫은 집합의 진부분집합</b>이었다.
     *
     * <p>그래서 벡터가 둘이다: 상한을 <b>안</b> 넘는 경고 하나(꼬리가 없으니 옛 접기가 아예 안
     * 걸리는 칸)와 넘는 것 하나. 도달은 여전히 0 이다(호출처 6/6 이 포트 상수) — 재는 것은
     * {@code origin} 을 공개 상수로 올리면서 넓어진 <b>계약</b>이다.
     */
    @Test
    @DisplayName("⭐ origin 이 줄을 쪼개지 않는다 — 상한 미달·초과 둘 다")
    void theLoggedOriginNeverBreaksTheLine() {
        String hostile = "chat:dict\n사전을 정본 것으로 갈아 끼웠다 — version=999";

        // (1) 상한 미달 — 경고 하나. 옛 배치에서는 접기가 아예 안 걸리던 칸이다.
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
     * ⭐ 모르는 입구는 <b>일반 분기</b>로 간다 — 그리고 그것이 안전한 쪽이다.
     *
     * <p>분기 키를 리터럴로 복제했을 때의 결함이 "상수가 바뀌면 조용히 일반 분기" 였는데, 일반
     * 분기 자신은 <b>틀린 안내를 하지 않는다</b>("그 출처를 직접 확인"). 위험은 정본에서 온
     * 입구가 그쪽으로 떨어져 <b>쓸 수 있는 조회 포인터를 잃는</b> 것이다. 그래서 위 세 테스트가
     * 상수를 참조한다.
     */
    @Test
    @DisplayName("모르는 입구는 일반 분기로 간다 — 틀린 안내를 하지는 않는다")
    void anUnknownOriginFallsBackWithoutLying() {
        assertThat(warnFor("chat:somewhere-else"))
                .contains("ADMIN 조회로 못 본다")
                .doesNotContain("/api/v1/chat/filter-words 로 받아 다시 센다");
    }
}
