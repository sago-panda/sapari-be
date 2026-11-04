package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

import reactor.core.publisher.Mono;
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
                changedSource, snapshotReader, new DictionaryProfanityFilterFactory(), cell);
    }

    private static ProfanityDictionarySnapshot snapshot(long version, String... words) {
        return new ProfanityDictionarySnapshot(version, Set.of(words), Set.of());
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
     * ⭐ <b>버전이 뒤로 가도 적용한다</b> — 한때 조용히 버렸다.
     *
     * <p>단조 비교가 {@code <=} 였을 때 이 경우가 <b>아무 로그도 없이</b> 버려졌다: 매 분 수십
     * KB 를 읽고 아무것도 교체하지 않는다. 도달 경로가 있다 — {@code FLUSHDB} · 옛 RDB 복원 ·
     * 낡은 레플리카 페일오버(버전이 1 부터 다시 시작). 그때 버리면 이 Pod 이 함대와 <b>영구히</b>
     * 갈린다.
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

        // Redis 가 상태를 잃었다 — 버전이 1 부터 다시 시작한다
        given(snapshotReader.readVersion()).willReturn(Mono.just(2L));
        given(snapshotReader.read()).willReturn(Mono.just(snapshot(2, "복원된욕설")));
        refresh("poll");

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
