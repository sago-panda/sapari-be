package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
     * ⭐ <b>접기로 가리킬 수 없는 행을 찍어 준다 — 막지는 않는다.</b>
     *
     * <p>이 티켓은 쓰기 경로가 접은 값을 저장하게 고쳤고 접기가 고정점임을 못 박았다. 그 둘은
     * <b>앞으로</b> 그런 행이 생기지 않게 할 뿐이고, <b>이미 있는 행</b>에는 둘 다 닿지 않는다 —
     * 이 브랜치 전의 쓰기 경로는 원문을 저장했다.
     *
     * <p>그 행 하나가 전파를 영구 동결시킨다: 전 Pod 이 교체를 거부하고, 다음 ADMIN 쓰기가 전부
     * 409 가 되고, 그 행을 지우려는 삭제는 접혀서 0행이다. 운영자가 보는 것은 "성공 응답과
     * 올라가는 버전, 그런데 아무것도 안 막힌다" 다. 탐지가 없으면 이 상태를 <b>알 방법 자체가
     * 없다.</b>
     *
     * <p>로그가 코드 포인트여야 한다. 문제가 보이지 않는 글자라서 평문으로 적으면 로그에서도
     * 안 보이고, 운영자는 무엇을 지워야 하는지 모른다.
     */
    @Test
    @DisplayName("⭐ 접기로 가리킬 수 없는 행을 코드 포인트로 찍는다 — 그래도 발행은 간다")
    void unaddressableRowsAreReportedWithCodePoints() {
        // given: 이 브랜치 전에 들어간, 선행 BOM 이 붙은 행
        given(repository.findWords(FilterWordKind.PROFANITY))
                .willReturn(Set.of("\uFEFF시발"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of());
        given(snapshotPublisher.currentVersion()).willReturn(7L);
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.of(8L));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger =
                (Logger) LoggerFactory.getLogger(ProfanityDictionaryPublishService.class);
        logger.addAppender(appender);
        try {
            // when: 막지 않는다 — 여기서 올리면 그 상태에서 부팅까지 실패해 동결이 더 굳는다
            assertThat(service.publishFromSource()).isEqualTo(8L);

            // then
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("이 상태가 조용하면 운영자는 전파 동결의 원인을 알 방법이 없다")
                    .anySatisfy(message -> assertThat(message)
                            .contains("가리킬 수 없는 행")
                            .as("평문으로 적으면 BOM 이 로그에서도 안 보인다")
                            .contains("U+FEFF")
                            .contains("U+C2DC"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("정상 정본에는 그 ERROR 가 없다 — 상시 ERROR 는 ERROR 를 죽인다")
    void aCleanSourceProducesNoUnaddressableReport() {
        given(repository.findWords(FilterWordKind.PROFANITY)).willReturn(Set.of("시발"));
        given(repository.findWords(FilterWordKind.WHITELIST)).willReturn(Set.of("시발점"));
        given(snapshotPublisher.currentVersion()).willReturn(0L);
        given(snapshotPublisher.publishIfUnchanged(anyLong(), anySet(), anySet()))
                .willReturn(OptionalLong.of(1L));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger =
                (Logger) LoggerFactory.getLogger(ProfanityDictionaryPublishService.class);
        logger.addAppender(appender);
        try {
            service.publishFromSource();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                    .as("멀쩡한 정본에서 ERROR 가 나면 당직자가 이 줄을 무시하게 된다")
                    .isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
