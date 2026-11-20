package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ProfanityDictionaryChangedSource;
import com.sapari.chat.application.port.ProfanityDictionarySnapshot;
import com.sapari.chat.application.port.ProfanityDictionarySnapshotReader;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.infrastructure.config.DictionaryProfanityFilterFactory;
import com.sapari.chat.infrastructure.config.SwappableProfanityFilter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.test.StepVerifier;

/**
 * 사전 새로고침 — <b>자가치유가 어느 순서에 걸려 있는지</b>를 잰다.
 *
 * <p>이 핸들러의 fail-safe 는 "실패하면 이전 사전을 계속 쓴다" 인데, 그것이 성립하려면
 * <b>로컬 버전이 교체 성공 뒤에만 올라가야</b> 한다. 먼저 올리면 그 Pod 은 다음 주기 확인에서
 * "일치" 로 보고 <b>영구히 재시도하지 않는다</b> — 주기 확인이 자가치유인 근거가 불일치가 남아
 * 있는 것이기 때문이다. 그래서 여기서 가장 세게 박는 단언이 "읽기 실패 뒤에 다시 시도한다" 다.
 *
 * <p>반대로 <b>내용 때문에</b> 거부된 버전은 다시 시도하지 않아야 한다. 다시 읽어도 같은 이유로
 * 거부되므로, 재시도하면 분마다 같은 ERROR 를 전 Pod 가 찍는다. 두 방향을 가르는 것이 이
 * 핸들러에서 가장 틀리기 쉬운 자리라 양쪽 다 벡터를 둔다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("사전 새로고침 — 실패의 방향이 재시도 여부를 가른다")
class ProfanityDictionaryRefreshHandlerTest {

    /** 반응형 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 멈추게 한다. */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Mock
    private ProfanityDictionaryChangedSource changedSource;
    @Mock
    private ProfanityDictionarySnapshotReader snapshotReader;

    private SwappableProfanityFilter cell;
    private ProfanityDictionaryRefreshHandler handler;

    /** 번들 자리를 대신하는 출발 사전 — 교체가 실제로 일어났는지는 이것이 바뀌는지로 본다. */
    private static final ProfanityFilter BUNDLED =
            new ProfanityFilter(Set.of("번들욕설"), Set.of());

    @BeforeEach
    void setUp() {
        cell = new SwappableProfanityFilter(BUNDLED);
        // 문은 실제 구현을 쓴다 — 목으로 바꾸면 "예산을 넘는 사전을 거부한다" 가 목의 설정을
        // 재는 테스트가 되고, 그 거부가 실제로 그 문에서 나오는지는 안 재진다.
        handler = new ProfanityDictionaryRefreshHandler(
                changedSource, snapshotReader, new DictionaryProfanityFilterFactory(), cell,
                ChatMetrics.NOOP);
    }

    private static ProfanityDictionarySnapshot snapshot(long version, String... words) {
        return new ProfanityDictionarySnapshot(version, Set.of(words), Set.of());
    }

    /**
     * ⭐ <b>알림 스트림이 {@code onError} 로 끝나도 주기 확인이 살아남는다.</b>
     *
     * <p>포트 javadoc 이 "이 스트림이 통째로 죽어도 전파는 주기 간격만큼 늦어질 뿐 멈추지
     * 않는다 — 그것이 설계의 요점이다" 를 <b>계약으로</b> 적는다. 그런데 {@code triggers()} 가
     * {@code Flux.merge} 라 한 소스의 {@code onError} 가 하류로 전파되고, 유일한
     * {@code onErrorResume} 은 {@code concatMap} <b>안쪽</b>이라 트리거 스트림 자신의 종료는
     * 못 잡는다. 그러면 {@code Flux.interval} 까지 같이 죽어 이 Pod 은 재시작 전까지 모든 사전
     * 변경을 놓치고 — 포트 javadoc 이 경계한 "어떤 Pod 은 막고 어떤 Pod 은 안 막는다" 가 된다.
     *
     * <p>이 클래스의 다른 벡터는 {@code refresh(reason)} 을 직접 부르므로
     * {@code start()}·{@code triggers()}·{@code subscribe()} 를 <b>한 번도 지나지 않는다</b> —
     * 그래서 이 축의 분모가 0 이었다. 여기서만 실제 구독을 돈다.
     */
    @Test
    @DisplayName("⭐ 알림 스트림이 죽어도 주기 확인은 살아남는다")
    void anErroredNotificationStreamDoesNotKillThePolling() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            Sinks.Many<Long> notifications = Sinks.many().multicast().directBestEffort();
            given(changedSource.changed()).willReturn(notifications.asFlux());
            given(snapshotReader.readVersion()).willReturn(Mono.just(1L));
            given(snapshotReader.read()).willReturn(Mono.just(snapshot(1, "정본욕설")));

            handler.start();
            notifications.tryEmitError(new RuntimeException("redis 구독이 끊겼다"));
            scheduler.advanceTimeBy(
                    ProfanityDictionaryRefreshHandler.VERSION_CHECK_INTERVAL.multipliedBy(3));

            // boot 1회 + 주기 3회. 알림이 죽어 merge 가 종료되면 boot 1회에서 멈춘다.
            then(snapshotReader).should(atLeast(3)).readVersion();
        } finally {
            handler.stop();
            VirtualTimeScheduler.reset();
        }
    }

    /**
     * ⭐ <b>알림이 연달아 와도 <i>교체가 겹치지 않는다</i> — {@code concatMap} 의 전제.</b>
     *
     * <p>그 전제가 네 자리에서 근거로 인용되는데(쓰는 스레드가 하나다 · 단조 비교 폐기 ·
     * {@code modules/chat/AGENTS.md} · ArchUnit 규칙 javadoc) <b>분모가 0 이었다</b> —
     * {@code concatMap} 을 {@code flatMap} 으로 바꿔도 전량이 초록이다. ArchUnit 규칙은
     * {@code install(..)} 의 두 번째 호출자만 막고 연산자 교체는 못 본다.
     *
     * <p>{@code flatMap} 이면 v5·v6 연속 알림이 둘 다 {@code published != processedVersion} 을
     * 통과해 병행으로 빌드되고, v6 이 먼저 끝나면 v5 가 뒤이어 <b>"뒤로 갔다" 분기로 집행하며
     * {@code processedVersion} 을 되돌린다.</b>
     *
     * <p>재는 것은 <b>{@code read()} 호출 수</b>다 — 설치는 {@code boundedElastic} 으로 떼어
     * 비동기지만, {@code readVersion}·{@code read} 는 알림을 넣은 스레드에서 돈다. 첫
     * {@code read()} 를 보류 중인 {@code Mono} 로 두면 직렬화되는 동안 호출이 <b>하나</b>고,
     * 겹치면 <b>둘</b>이다. 시간에 기대지 않는다.
     */
    @Test
    @DisplayName("⭐ 알림이 연달아 와도 교체가 겹치지 않는다")
    void backToBackNotificationsAreSerialized() {
        // ⚠️ 버퍼링 싱크다. directBestEffort 는 concatMap 이 한 번에 하나만 요청하는 동안
        // 두 번째 알림을 <드롭>하므로 "큐에 들어간다" 를 못 재고 다른 것을 재게 된다.
        Sinks.Many<Long> notifications = Sinks.many().replay().all();
        Sinks.One<ProfanityDictionarySnapshot> firstRead = Sinks.one();
        given(changedSource.changed()).willReturn(notifications.asFlux());
        given(snapshotReader.readVersion())
                .willReturn(Mono.empty(), Mono.just(5L), Mono.just(6L));
        given(snapshotReader.read())
                .willReturn(firstRead.asMono(), Mono.just(snapshot(6, "여섯번욕설")));

        handler.start();
        try {
            notifications.tryEmitNext(5L);
            notifications.tryEmitNext(6L);

            then(snapshotReader).should(times(1)).read();

            // 보류를 풀면 큐에 있던 두 번째가 이어 돈다. 설치가 boundedElastic 으로 떼어져
            // 있어 그 사이에 비동기 구간이 끼므로 상한을 걸고 기다린다 — 큐가 사라지지 않았다는
            // 것만 재고 타이밍은 재지 않는다.
            firstRead.tryEmitValue(snapshot(5, "다섯번욕설"));
            then(snapshotReader).should(timeout(VERIFY_CEILING.toMillis()).times(2)).read();
        } finally {
            handler.stop();
        }
    }

    private void refresh(String reason) {
        StepVerifier.create(handler.refresh(reason)).expectComplete().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ 스냅샷이 있으면 번들 사전을 정본 것으로 갈아 끼운다")
    void appliesThePublishedSnapshot() {
        // given
        given(snapshotReader.readVersion()).willReturn(Mono.just(1L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(1, "정본욕설")));

        // when
        refresh("boot");

        // then: 셀이 든 필터가 바뀌었다 — 번들 단어는 더 안 걸리고 정본 단어가 걸린다
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
        assertThat(cell.current().filter("번들욕설")).isEqualTo("번들욕설");
    }

    @Test
    @DisplayName("버전이 그대로면 스냅샷을 끌어오지 않는다 — 주기 확인이 수십 KB 를 매분 당기지 않는다")
    void anUnchangedVersionDoesNotFetchTheSnapshot() {
        // given: 한 번 적용해 로컬 버전을 3으로 만든다
        given(snapshotReader.readVersion()).willReturn(Mono.just(3L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(3, "정본욕설")));
        refresh("boot");

        // when: 같은 버전으로 주기 확인
        refresh("poll");

        // then: read() 는 처음 한 번뿐이다
        then(snapshotReader).should(times(1)).read();
    }

    /**
     * ⭐ <b>자가치유의 하중점.</b> 읽기가 실패했는데 로컬 버전을 올리면 그 Pod 은 다음 확인에서
     * "일치" 로 보고 영구히 재시도하지 않는다. 그러면 알림을 놓친 Pod 이 재시작할 때까지 옛
     * 사전으로 집행하고, 증상이 "어떤 Pod 은 막고 어떤 Pod 은 안 막는다" 라 원인이 사전 쪽으로
     * 수렴하지 않는다.
     */
    @Test
    @DisplayName("⭐ 읽기가 실패하면 버전을 올리지 않는다 — 다음 확인이 다시 시도한다")
    void aTransportFailureDoesNotAdvanceTheVersion() {
        // given: 첫 시도는 전송 실패
        given(snapshotReader.readVersion()).willReturn(Mono.just(5L));
        given(snapshotReader.read()).willReturn(Mono.error(new RuntimeException("redis blip")));
        refresh("notify:v5");

        assertThat(cell.current().filter("번들욕설"))
                .as("실패했으면 이전 사전을 계속 쓴다")
                .isEqualTo("***");

        // when: 같은 버전으로 다시 — 재시도해야 한다
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(5, "정본욕설")));
        refresh("poll");

        // then
        then(snapshotReader).should(times(2)).read();
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
    }

    /**
     * ⭐ 반대 방향 — <b>내용</b> 때문에 거부된 버전은 다시 시도하지 않는다.
     *
     * <p>{@code ProfanityDictionary.of} 는 순수 계산이라 같은 스냅샷을 다시 읽으면 같은 이유로
     * 거부된다. 재시도하면 분마다 같은 ERROR 를 전 Pod 가 찍는다. 그 스냅샷이 고쳐지면 새
     * 발행이고 버전이 오르므로 그때 다시 받는다 — 아래 {@code aNewerVersionIsPickedUp} 가 그것이다.
     */
    @Test
    @DisplayName("⭐ 예산을 넘는 항목이 든 사전은 거부하고, 같은 버전을 다시 시도하지 않는다")
    void aRefusedSnapshotIsNotRetried() {
        // given: 17자 — K 예산(16) 밖이다
        given(snapshotReader.readVersion()).willReturn(Mono.just(7L));
        given(snapshotReader.read())
                .willReturn(Mono.just(snapshot(7, "가".repeat(17))));

        // when
        refresh("notify:v7");

        // then: 교체 거부 — 이전 사전이 그대로다
        assertThat(cell.current().filter("번들욕설")).isEqualTo("***");

        // when: 같은 버전으로 주기 확인
        refresh("poll");

        // then: 두 번째 read 가 없다
        then(snapshotReader).should(times(1)).read();
    }

    @Test
    @DisplayName("거부된 뒤에 새 버전이 오면 받는다 — 고치는 길이 막히지 않는다")
    void aNewerVersionIsPickedUpAfterARefusal() {
        // given: 거부되는 사전
        given(snapshotReader.readVersion()).willReturn(Mono.just(7L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(7, "가".repeat(17))));
        refresh("notify:v7");

        // when: 운영자가 고쳐 새로 발행했다
        given(snapshotReader.readVersion()).willReturn(Mono.just(8L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(8, "정본욕설")));
        refresh("notify:v8");

        // then
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
    }

    @Test
    @DisplayName("포맷이 깨진 스냅샷도 거부하고 같은 버전을 다시 시도하지 않는다")
    void aMalformedSnapshotIsNotRetried() {
        // given: 코덱이 던지는 상황을 reader 가 그대로 올린다
        given(snapshotReader.readVersion()).willReturn(Mono.just(9L));
        given(snapshotReader.read())
                .willReturn(Mono.error(new IllegalStateException("사전 스냅샷의 줄 수가 선언과 다르다")));

        refresh("poll");
        refresh("poll");

        then(snapshotReader).should(times(2)).readVersion();
        then(snapshotReader).should(times(1)).read();
        assertThat(cell.current().filter("번들욕설")).isEqualTo("***");
    }

    @Test
    @DisplayName("스냅샷이 아직 없으면 번들 사전으로 남는다 — 실패가 아니다")
    void noSnapshotYetLeavesTheBundledDictionary() {
        given(snapshotReader.readVersion()).willReturn(Mono.empty());

        refresh("boot");

        then(snapshotReader).should(never()).read();
        assertThat(cell.current().filter("번들욕설")).isEqualTo("***");
    }

    /**
     * ⭐ <b>머리 0 짜리 본문은 Pod 상태로 갈린다 — 한 문장으로 단정할 수 없는 자리.</b>
     *
     * <p>⚠️ 발행 쪽이 이 축을 <b>테스트 없이 단정했다</b>. {@code tokenOf} 의 값 0 WARN 이
     * "함대가 본문을 당겨도 머리가 0 이라 적용을 조용히 건너뛴다 — 번들 사전으로 돈다" 로
     * 적혀 있었는데, 그것은 {@code processedVersion == 0} 인 Pod 에서만 참이다. 이미 적용한
     * Pod 은 {@code 0 < processedVersion} 이라 <b>"뒤로 갔다" WARN 과 함께 그 본문을
     * 집행한다</b>(위 {@link #aRegressedVersionIsStillApplied} 가 못 박은 의도된 설계).
     *
     * <p>아래 두 칸이 그 갈림의 양쪽이다. 발행자는 이 축을 볼 수 없으므로(그쪽은 Redis 두
     * 키만 본다) 결과절에서 <b>Pod 상태를 열거하지 않고</b> "갈린다 · 복구는 재발행 · 재시작
     * 전에 본문을 확인" 만 말한다. 상태를 열거하면 목록 밖의 칸이 남고, 재시작을 포괄
     * 금지하면 밴드 밖 본문이 집행 중인 Pod 에서 유일한 즉시 수단을 막는다.
     *
     * <p>이 두 칸을 여기서 재는 이유: 이 클래스의 {@code readVersion()} 벡터에 <b>머리가 0 인
     * 칸이 없었다.</b> 발행 쪽 테스트는 WARN 문자열만 보므로 수신 동작을 아무도 안 쟀다.
     */
    @Test
    @DisplayName("⭐ 머리 0 본문은 갓 뜬 Pod 은 건너뛰고 기동 중 Pod 은 시끄럽게 집행한다")
    void aZeroHeadedBodySplitsTheFleetByPodState() {
        // (1) 갓 뜬 Pod — processedVersion 이 0 이라 머리 0 과 같아 건너뛴다
        given(snapshotReader.readVersion()).willReturn(Mono.just(5L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(0, "정본욕설")));
        ListAppender<ILoggingEvent> fresh = attachAppender();
        try {
            refresh("boot");

            assertThat(cell.current().filter("정본욕설"))
                    .as("머리 0 == processedVersion 0 이라 적용 분기가 건너뛴다 — 번들로 돈다")
                    .isEqualTo("정본욕설");
            assertThat(cell.current().filter("번들욕설")).isEqualTo("***");
            assertThat(fresh.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("이 칸이 수신 쪽에서 무음이다 — 그래서 발행 쪽이 말해야 한다")
                    .isEmpty();
        } finally {
            detachAppender(fresh);
        }

        // (2) 같은 Pod 이 정상 버전을 한 번 받은 뒤 — 이제 processedVersion 이 0 이 아니다
        given(snapshotReader.readVersion()).willReturn(Mono.just(3L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(3, "정상욕설")));
        refresh("poll");

        given(snapshotReader.readVersion()).willReturn(Mono.just(5L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(0, "머리0욕설")));
        ListAppender<ILoggingEvent> running = attachAppender();
        try {
            refresh("poll");

            assertThat(running.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("기동 중 Pod 은 0 < processedVersion 이라 <뒤로 갔다> 분기로 간다 — "
                            + "조용하지도 번들도 아니다")
                    .anySatisfy(message -> assertThat(message).contains("뒤로 갔다"));
        } finally {
            detachAppender(running);
        }

        assertThat(cell.current().filter("머리0욕설"))
                .as("그 본문을 실제로 집행한다 — 발행 쪽 결과절이 '번들로 돈다' 로 단정하면 "
                        + "이 절반을 거짓으로 말한다")
                .isEqualTo("***");
    }

    /**
     * ⭐ <b>버전이 뒤로 가도 적용한다</b> — 한때 조용히 버렸다.
     *
     * <p>단조 비교가 {@code <=} 였을 때 이 경우가 <b>아무 로그도 없이</b> 버려졌다: 매 분 수십
     * KB 를 읽고 아무것도 교체하지 않는다. 도달 경로가 있다 — 키에 큰 수가 손으로 들어간 뒤
     * 두 키를 잃거나, 시계가 뒤로 간 뒤 두 키를 잃는 경우(발행 번호의 하한이 발행 시각이다).
     * 그때 버리면 이 Pod 이 함대와 <b>영구히</b> 갈린다.
     *
     * <p>적용이 맞는 이유: 이 Pod 이 가진 유일한 정본 사본이 발행된 스냅샷이고(정본은 테이블인데
     * streaming-app 에는 DataSource 가 없다), 새로고침이 직렬화돼 있어(triggers →
     * {@code concatMap}) 순서가 뒤집힐 경로가 없다. 그 비교는 방어였고 대가가 무음이었다.
     */
    @Test
    @DisplayName("⭐ 버전이 뒤로 가도 적용한다 — 조용히 버리면 Pod 이 함대와 영구히 갈린다")
    void aRegressedVersionIsStillApplied() {
        given(snapshotReader.readVersion()).willReturn(Mono.just(4L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(4, "정본욕설")));
        refresh("boot");

        // Redis 가 상태를 잃었고 새 번호가 이 Pod 이 든 수보다 작다
        given(snapshotReader.readVersion()).willReturn(Mono.just(2L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(2, "복원된욕설")));
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            refresh("poll");

            // ⭐ 적용만 재면 <WARN 을 지우는 변경>이 조용히 지나간다. 이 분기의 원래 결함이
            // "조용히 버린다" 였으므로 적용과 소리가 둘 다 성질이다 — 뒤로 간 버전을 적용하는
            // 것은 Redis 가 상태를 잃었다는 신호이고, 그것이 로그에 없으면 아무도 모른다.
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("버전이 뒤로 간 것을 조용히 적용하면 Redis 상태 유실이 흔적 없이 지나간다")
                    .anySatisfy(message -> assertThat(message).contains("뒤로 갔다"));
        } finally {
            detachAppender(appender);
        }

        assertThat(cell.current().filter("복원된욕설"))
                .as("이 Pod 이 가진 유일한 정본 사본이 발행된 스냅샷이다 — 버리면 이 Pod 만 "
                        + "옛 사전으로 남고 정본과 다시 맞출 경로가 없다")
                .isEqualTo("***");
        assertThat(cell.current().filter("정본욕설")).isEqualTo("정본욕설");
    }

    /** 같은 버전을 다시 받으면 교체하지 않는다 — 그쪽은 여전히 무동작이 맞다. */
    @Test
    @DisplayName("같은 버전을 다시 받으면 교체하지 않는다")
    void theSameVersionIsNotReapplied() {
        given(snapshotReader.readVersion()).willReturn(Mono.just(4L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(4, "정본욕설")));
        refresh("boot");

        // 확인 때 본 수는 다르지만 손에 든 스냅샷은 같은 버전이다
        given(snapshotReader.readVersion()).willReturn(Mono.just(9L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(4, "다른욕설")));
        refresh("poll");

        assertThat(cell.current().filter("다른욕설")).isEqualTo("다른욕설");
    }

    /**
     * ⭐ <b>"버전은 있고 본문은 없다" 가 한때 유일하게 완전 무음인 분기였다.</b>
     *
     * <p>{@code readVersion()} 이 값을 주면 "스냅샷이 아직 없다" 분기를 타지 않고, {@code read()}
     * 가 빈 {@code Mono} 면 체인이 그대로 비어 끝난다 — 에러도 로그도 없고 버전도 안 올라
     * 1분마다 조용히 반복한다. 다른 모든 분기에는 로그가 있었다.
     *
     * <p>도달 경로: 두 키 모두 TTL 이 없지만 {@code allkeys-lru} 는 TTL 없는 키도 축출하고,
     * 축출 순서가 스냅샷에 불리하다 — 수십 KB · 변경 시에만 읽힘 vs 몇 바이트 · 분마다 읽힘.
     */
    @Test
    @DisplayName("⭐ 버전은 있고 본문이 없으면 교체하지 않되 조용하지 않다")
    void aMissingBodyWithAPresentVersionIsNotSilent() {
        // given: 버전은 읽히는데 본문 키가 없다
        given(snapshotReader.readVersion()).willReturn(Mono.just(3L));
        given(snapshotReader.read()).willReturn(Mono.empty());
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            // when
            refresh("poll");
            refresh("poll");

            // then: 버전을 올리지 않으므로 계속 시도한다 — 다음 발행이 본문을 되살리면 받는다
            then(snapshotReader).should(times(2)).read();
            assertThat(cell.current().filter("번들욕설")).isEqualTo("***");

            // ⭐ 그리고 조용하지 않다. 이것이 이 분기의 결함이었다 — 지적의 내용이 "교체하지
            // 않는다" 가 아니라 "아무 로그도 없다" 였으므로, 동작만 재면 로그를 지우는 변경이
            // 그 결함을 되살린다.
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList())
                    .as("버전은 있고 본문이 없는 상태가 조용하면 1분마다 반복되고 아무도 모른다")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message).contains("본문이 없다"));
        } finally {
            detachAppender(appender);
        }

        // 되살아나면 적용된다
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(3, "정본욕설")));
        refresh("poll");
        assertThat(cell.current().filter("정본욕설")).isEqualTo("***");
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ProfanityDictionaryRefreshHandler.class))
                .addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ProfanityDictionaryRefreshHandler.class))
                .detachAppender(appender);
        appender.stop();
    }

    /**
     * ⭐ 트라이 빌드가 호출 스레드에서 돌지 않는다.
     *
     * <p>1,078 항목에서 p50 0.54 ms · max 1.45 ms 이고 항목 수에 선형이다. 알림은 Redis 구독
     * 스레드에서, 주기는 parallel 스케줄러에서 오는데 둘 다 그 일을 할 자리가 아니다 —
     * 구독 스레드를 붙잡으면 그 Pod 의 다른 Pub/Sub 수신이 함께 밀린다.
     */
    @Test
    @DisplayName("⭐ 트라이 빌드는 boundedElastic 에서 돈다 — 구독 스레드를 붙잡지 않는다")
    void theTrieIsBuiltOffTheCallingThread() {
        // given: 사전 문이 항목을 순회할 때의 스레드를 잡는다. 셀이 final 이라 install 을 가로챌 수
        // 없고, doFinally 는 '완료한 스레드' 라 빌드가 돈 자리와 다를 수 있다 — 순회가
        // ProfanityDictionary.of 안에서 일어나므로 이 자리가 빌드 그 자체다.
        AtomicReference<String> buildThread = new AtomicReference<>();
        Set<String> probed = new AbstractSet<>() {
            private final Set<String> delegate = Set.of("정본욕설");

            @Override
            public Iterator<String> iterator() {
                buildThread.set(Thread.currentThread().getName());
                return delegate.iterator();
            }

            @Override
            public int size() {
                return delegate.size();
            }
        };
        given(snapshotReader.readVersion()).willReturn(Mono.just(1L));
        given(snapshotReader.read())
                .willReturn(Mono.just(new ProfanityDictionarySnapshot(1, probed, Set.of())));

        // when
        refresh("notify:v1");

        // then
        assertThat(buildThread.get())
                .as("빌드가 호출 스레드에서 돌면 그 구독 스레드가 그만큼 붙잡힌다")
                .startsWith("boundedElastic-");
        assertThat(cell.current().filter("정본욕설"))
                .as("스레드를 뗐어도 교체는 실제로 일어나야 한다")
                .isEqualTo("***");
    }

    @Test
    @DisplayName("주기 확인 간격은 분 단위다 — 알림을 놓친 Pod 이 다른 사전으로 집행하는 창")
    void theVersionCheckIntervalIsBounded() {
        assertThat(ProfanityDictionaryRefreshHandler.VERSION_CHECK_INTERVAL)
                .as("이 값이 '알림을 놓친 Pod 이 얼마나 오래 틀린 사전으로 집행하는가' 다")
                .isEqualTo(Duration.ofMinutes(1));
    }
}
