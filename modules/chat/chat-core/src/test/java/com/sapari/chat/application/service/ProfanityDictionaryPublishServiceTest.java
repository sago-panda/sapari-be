package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * 정본 → 스냅샷 — <b>두 종류가 함께 나가는지</b>를 잰다.
 *
 * <p>한쪽만 발행하면 받는 쪽이 반쪽 사전으로 교체한다. 화이트리스트 항목은 자기가 덮는 욕설과
 * 함께여야 의미가 있으므로, 욕설만 보내면 그 항목들이 보호하던 정상어가 통째로 마스킹된다
 * ({@code 시발점} → {@code ***점}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("사전 발행 — 두 종류를 함께 떠서 보낸다")
class ProfanityDictionaryPublishServiceTest {

    @Mock
    private ChatFilterWordRepository repository;
    @Mock
    private ProfanityDictionarySnapshotPublisher snapshotPublisher;

    private ProfanityDictionaryPublishService service;

    @BeforeEach
    void setUp() {
        // 문은 실제 구현을 쓴다 — 접기 평면을 목으로 바꾸면 "가리킬 수 없는 행" 판정이
        // 목 설정을 재는 테스트가 된다.
        service = new ProfanityDictionaryPublishService(
                repository, snapshotPublisher, new DictionaryProfanityFilterFactory());
    }

    @Test
    @DisplayName("⭐ 욕설과 화이트리스트를 함께 발행하고 새 버전을 돌려준다")
    void publishesBothKindsAndReturnsTheVersion() {
        // given
        given(snapshotPublisher.currentVersion()).willReturn(11L);
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of("시발"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of("시발점"));
        given(snapshotPublisher.publishIfUnchanged(11L, Set.of("시발"), Set.of("시발점")))
                .willReturn(OptionalLong.of(12L));

        // when
        long version = service.publishFromSource();

        // then: 버전을 돌려줘야 호출자가 "내가 밀어 넣은 것이 함대에 깔렸는가" 를 받는 쪽 로그와
        // 맞출 수 있다
        assertThat(version).isEqualTo(12);
        verify(snapshotPublisher).publishIfUnchanged(11L, Set.of("시발"), Set.of("시발점"));
    }

    /**
     * ⭐ <b>조건이 깨지면 정본을 다시 읽는다 — 같은 본문으로 다시 던지지 않는다.</b>
     *
     * <p>이것이 이 재시도의 전부다. 같은 본문을 재전송하면 낡은 본문을 밀어 넣는 것이고,
     * 조건부 발행이 고치려던 결함을 그대로 재현한다 — 그래서 재시도가 어댑터가 아니라 정본을
     * 쥔 이 서비스에 있다.
     *
     * <p>벡터: 1회차에 {X} 를 들고 밀렸다가, 2회차에 다시 읽어 {X,Y} 로 성공한다.
     */
    @Test
    @DisplayName("⭐ 다른 발행에 밀리면 정본을 다시 읽어 재시도한다 — 같은 본문을 재전송하지 않는다")
    void aLostRaceRereadsTheSource() {
        // given: 1회차는 v5 를 집고 {시발} 을 읽어 밀린다. 2회차는 v6 을 집고 {시발,지랄} 을 읽는다.
        given(snapshotPublisher.currentVersion()).willReturn(5L, 6L);
        given(repository.findWords(FilterWordKind.PROFANITY))
                .willReturn(Set.of("시발"), Set.of("시발", "지랄"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.publishIfUnchanged(5L, Set.of("시발"), Set.of()))
                .willReturn(OptionalLong.empty());
        given(snapshotPublisher.publishIfUnchanged(6L, Set.of("시발", "지랄"), Set.of()))
                .willReturn(OptionalLong.of(7L));

        // when
        long version = service.publishFromSource();

        // then: 두 번째 본문이 나갔다 — 첫 본문을 그대로 재전송하면 지랄이 함대에서 빠진다
        assertThat(version).isEqualTo(7);
        verify(snapshotPublisher).publishIfUnchanged(6L, Set.of("시발", "지랄"), Set.of());
        verify(repository, times(2)).findWords(FilterWordKind.PROFANITY);
    }

    /**
     * 상한까지 밀리면 실패로 알린다. 조용히 포기하면 호출자가 발행됐다고 믿고 정본과 함대가
     * 갈린 채로 끝난다 — 관리자 쓰기 경로에서는 그게 "성공 응답 + 아무 Pod 도 집행 안 함" 이다.
     */
    @Test
    @DisplayName("상한까지 밀리면 던진다 — 조용히 포기하면 정본과 함대가 갈린다")
    void exhaustedRetriesThrow() {
        given(snapshotPublisher.currentVersion()).willReturn(1L);
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of("시발"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.empty());

        assertThatThrownBy(() -> service.publishFromSource())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연속 다른 발행에 밀렸다");
    }

    /**
     * 정본이 빈 것을 발행하는 것 자체는 막지 않는다 — 거부는 받는 쪽 문
     * ({@code ProfanityDictionary.of})이 하고, 거기서 떨어지면 교체를 포기한다. 여기서 막으면
     * "정본이 정말 비었다" 를 발행할 길이 없어지고, 그 상태는 운영자가 전부 지운 결과일 수 있다.
     */
    @Test
    @DisplayName("빈 정본도 발행한다 — 거부는 받는 쪽 문이 한다")
    void anEmptySourceIsStillPublished() {
        given(snapshotPublisher.currentVersion()).willReturn(0L);
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of());
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.publishIfUnchanged(0L, Set.of(), Set.of()))
                .willReturn(OptionalLong.of(1L));

        assertThat(service.publishFromSource()).isEqualTo(1);
    }

    /**
     * ⭐ <b>문이 거부하는 행(동결)과 통과시키는 행(그림자)을 갈라 적는다.</b>
     *
     * <p>한때 한 문장이 셋을 같이 단정했다 — "(1) 전 Pod 이 교체를 거부 (2) 모든 ADMIN 쓰기가
     * 409 (3) 삭제가 0행". 실측하니 <b>선행 BOM 행에서는 (1)·(2)가 일어나지 않는다</b>:
     * {@code of()} 가 {@code strip} 뒤에 거부하므로 접기가 그 글자를 떼어 문에 닿지 않는다
     * (전수 — 글자 여섯 × 선행·후행·내부 = 18칸, rejected=15 · passed=3. 조용히 떼어지는 세
     * 칸은 선행 공백 · 후행 공백 · 선행 BOM 이다). 당직자가 그 ERROR 로 함대 동결을 의심하면
     * 엉뚱한 곳을 본다.
     *
     * <p>그 행에서 <b>실제로</b> 일어나는 것이 메시지에 없었다. {@code uk (kind, word)} 가 원문
     * 기준이라 테이블이 {@code 시발} 과 {@code <BOM>시발} 을 같이 들 수 있고, 삭제가 한 행만
     * 지우고 성공을 내는데 남은 행이 같은 단어를 계속 집행한다.
     *
     * <p>⚠️ <b>그리고 이 테스트가 판정축까지 고쳤다.</b> 처음에는 두 부류를 "접기 불일치"
     * 하나로 찾고 문에게 물어 가르려 했는데, 이 벡터에서 동결 쪽({@code 시발<BOM>})이 <b>아예
     * 걸리지 않았다</b> — {@code normalize("시발<BOM>")} 이 자기 자신이라 불일치가 아니다. 두
     * 부류는 포함관계가 아니라 직교이고, 그래서 동결은 <b>문에게 전체 집합을 한 번 묻고</b>
     * 그림자는 <b>행마다 접어서</b> 찾는다.
     */
    @Test
    @DisplayName("⭐ 문이 거부하는 행은 '동결' 로, 통과시키는 행은 '그림자' 로 적는다")
    void theTwoClassesGetDifferentMessages() {
        // 후행 BOM 은 접기가 보존하므로 문이 거부한다(동결).
        // 선행 BOM 은 접기가 떼므로 문을 통과한다(그림자).
        given(repository.findWords(FilterWordKind.PROFANITY))
                .willReturn(new LinkedHashSet<>(List.of("시발\uFEFF", "\uFEFF지랄")));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.currentVersion()).willReturn(0L);
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.of(1L));

        List<String> errors = captureErrors(() ->
                assertThat(service.publishFromSource())
                        .as("막으면 그 상태에서 발행이 아예 못 돌아 동결이 한 겹 더 굳는다")
                        .isEqualTo(1L));

        assertThat(errors)
                .as("두 부류의 처방이 다르므로 한 문장으로 뭉개면 당직자가 엉뚱한 곳을 본다")
                .hasSize(2);
        assertThat(errors)
                .anySatisfy(message -> assertThat(message)
                        .contains("정본이 사전 문을 통과하지 못한다")
                        .contains("409")
                        .as("접기가 그 글자를 보존하므로 API 로 지울 수 있다 — 복구가 수동 SQL 이 아니다")
                        .contains("API")
                        .as("문의 메시지가 어느 항목인지를 담는다 — 보이지 않는 글자만 \\uXXXX 로")
                        .contains("\\uFEFF"))
                .anySatisfy(message -> assertThat(message)
                        .contains("그림자 행")
                        .as("이쪽에서 일어나는 것은 '삭제가 성공으로 보이는데 계속 집행된다' 다")
                        .contains("한 행만 지우고")
                        .as("동결 쪽 문면이 섞이면 처방이 틀어진다")
                        .doesNotContain("409"));
    }

    @Test
    @DisplayName("정상 정본에는 그 ERROR 가 없다 — 상시 ERROR 는 ERROR 를 죽인다")
    void aCleanSourceProducesNoUnaddressableReport() {
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of("시발"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of("시발점"));
        given(snapshotPublisher.currentVersion()).willReturn(0L);
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.of(1L));

        assertThat(captureErrors(service::publishFromSource))
                .as("멀쩡한 정본에서 ERROR 가 나면 당직자가 이 줄을 무시하게 된다")
                .isEmpty();
    }

    /**
     * ⭐ <b>한 줄에 상한이 있다.</b>
     *
     * <p>상한이 없을 때 전량이 걸리면 한 줄이 35,966자였다(측정: 욕설 1,078 · 화이트리스트 38).
     * 평시 0건이라 안 드러나는데, 접기 평면을 바꾸는 변경이 들어오면 <b>모든 행이 불일치</b>가
     * 되어 발행 1회당 그 줄이 나간다. 그리고 그때 부류를 가르려고 문을 1,116번 세우는 것도
     * 같이 막아야 한다.
     */
    @Test
    @DisplayName("⭐ 보고가 상한에서 끊긴다 — 평면을 바꾸는 변경이 들어오면 전 행이 걸린다")
    void theReportIsCapped() {
        Set<String> many = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            // 선행 BOM = 그림자 축. 후행 BOM 은 접기가 보존해 불일치가 아니다(그 축이 아니다).
            many.add("\uFEFF시발" + i);
        }
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(many);
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.currentVersion()).willReturn(0L);
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.of(1L));

        List<String> errors = captureErrors(service::publishFromSource);

        assertThat(errors).hasSize(1);
        // ⚠️ "상한 20건" 이라는 접미만 단언하면 상한이 집히지 않는다 — 상한을 지워도 그 접미는
        // 그대로 붙는다(실측: 변이가 통과했다). 집어야 하는 것은 <나열한 수>다.
        assertThat(errors.get(0))
                .contains("그림자 행")
                .as("전체 수는 그대로 알려야 한다 — 상한은 나열에만 걸린다")
                .contains("전체 50건 중 20건")
                .as("상한을 넘겼다는 사실이 없으면 50건을 다 봤다고 읽는다")
                .contains("상한 20건");
        assertThat(errors.get(0).split(" · "))
                .as("나열이 상한에서 끊기지 않으면 한 줄이 35,966자까지 간다")
                .hasSize(20);
    }

    /**
     * ⭐ <b>정확히 상한만큼일 때 "더 있다" 고 말하지 않는다.</b>
     *
     * <p>{@code capped()} 가 {@code total} 이 아니라 상한을 보고 있었다. 그러면 그림자 행이
     * 정확히 20건일 때 {@code 전체 20건 중 20건: … 외 더 있음} 이 나간다 — 당직자가 20건을 다
     * 보고도 더 있다고 읽고 SQL 로 다시 센다. 위 테스트가 50건만 재서 이 경계가 분모에 없었다.
     */
    @Test
    @DisplayName("⭐ 정확히 상한만큼이면 '외 N건' 이 없다 — 다 보여 준 것을 더 있다고 읽게 하면 안 된다")
    void exactlyTheLimitSaysNothingMoreRemains() {
        Set<String> exactly = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            exactly.add("\uFEFF시발" + i);
        }
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(exactly);
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.currentVersion()).willReturn(0L);
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.of(1L));

        List<String> errors = captureErrors(service::publishFromSource);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .contains("전체 20건 중 20건")
                .as("다 보여 줬는데 더 있다고 말하면 당직자가 SQL 로 다시 센다")
                .doesNotContain("외 ");
    }

    private static List<String> captureErrors(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger =
                (Logger) LoggerFactory.getLogger(ProfanityDictionaryPublishService.class);
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
